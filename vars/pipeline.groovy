/**
 * pipeline.groovy
 *
 * Pipeline utilities and orchestration templates for Jenkins pipelines.
 *
 * Provides three categories of functions:
 *
 * 1. Parameter and utility helpers:
 *    - resolvePipelineParams   — Parse JOB_NAME, resolve branches/repos/timeouts.
 *    - standardDockerCleanup   — Stop containers, remove images, delete volumes.
 *    - standardCredentialLoader — Load credential sets by target environment.
 *
 * 2. Pipeline templates (high-level orchestration):
 *    - airgapInfraPipeline     — Airgap infra: checkout → provision → body → teardown.
 *    - airgapTestPipeline      — Airgap tests: infra + container test execution.
 *    - simpleTestPipeline      — Standard test runner: checkout → build → test → report.
 *
 * Usage
 * -----
 *   def params = pipeline.resolvePipelineParams()
 *
 *   pipeline.standardCredentialLoader('aws') {
 *       pipeline.airgapInfraPipeline(
 *           params: params,
 *           workspaceName: wsName,
 *           tofuConfig: [...]
 *       ) { outputs ->
 *           // custom stages using outputs
 *       }
 *   }
 */

// ---------------------------------------------------------------------------
// Utility functions
// ---------------------------------------------------------------------------

/**
 * Parse the Jenkins JOB_NAME and resolve standard pipeline parameters from
 * environment variables with sensible defaults.
 *
 * Extracts the short job name (last segment after '/') and resolves commonly
 * used parameter values. Caller-supplied overrides take highest precedence,
 * then environment variables, then config defaults, then hardcoded defaults.
 *
 * Parameters:
 *   overrides (Map, optional) - Explicit values that take precedence over
 *                               environment variables and defaults. Supported keys:
 *                                 branch, repo, timeout, testsBranch, infraBranch,
 *                                 testsRepo, infraRepo, jobName
 *
 * Returns a Map with keys:
 *   jobName     (String) - Short job name (last segment of JOB_NAME).
 *   branch      (String) - QA Jenkins Library branch.
 *   repo        (String) - QA Jenkins Library repo URL.
 *   timeout     (int)    - Timeout in minutes.
 *   testsBranch (String) - Rancher/tests branch.
 *   testsRepo   (String) - Rancher/tests repo URL.
 *   infraBranch (String) - qa-infra-automation branch.
 *   infraRepo   (String) - qa-infra-automation repo URL.
 *
 * Example:
 *   def params = pipeline.resolvePipelineParams()
 *   // params.testsBranch → 'main' (or env override)
 *
 *   def params = pipeline.resolvePipelineParams(
 *       overrides: [testsBranch: 'release/v2.9', timeout: 180]
 *   )
 */
def resolvePipelineParams(Map params = [:]) {
    def overrides = params.overrides ?: [:]

    def libConfig = new config()
    def pipelineCfg = libConfig.getPipelineConfig()
    def repoCfg = libConfig.getConfig('repositories')

    // Extract short job name
    def fullJobName = env.JOB_NAME ?: 'unknown'
    def jobName = fullJobName
    if (fullJobName.contains('/')) {
        def segments = fullJobName.split('/')
        jobName = segments[segments.size() - 1]
    }

    def resolved = [
        jobName:     overrides.jobName     ?: jobName,
        branch:      overrides.branch      ?: (env.QA_JENKINS_LIBRARY_BRANCH ?: 'main'),
        repo:        overrides.repo        ?: (env.QA_JENKINS_LIBRARY_REPO_URL ?: 'https://github.com/rancher/qa-jenkins-library'),
        timeout:     (overrides.timeout    ?: (env.TIMEOUT ?: pipelineCfg.defaultTimeout ?: '120')).toInteger(),
        testsBranch: overrides.testsBranch ?: (env.RANCHER_TESTS_REPO_BRANCH ?: repoCfg?.tests?.branch ?: 'main'),
        testsRepo:   overrides.testsRepo   ?: (env.RANCHER_TESTS_REPO_URL ?: repoCfg?.tests?.url ?: 'https://github.com/rancher/tests'),
        infraBranch: overrides.infraBranch ?: (env.QA_INFRA_AUTOMATION_REPO_BRANCH ?: repoCfg?.qaInfraAutomation?.branch ?: 'main'),
        infraRepo:   overrides.infraRepo   ?: (env.QA_INFRA_AUTOMATION_REPO_URL ?: repoCfg?.qaInfraAutomation?.url ?: 'https://github.com/rancher/qa-infra-automation')
    ]

    steps.echo "Resolved pipeline params: job=${resolved.jobName}, tests=${resolved.testsBranch}, infra=${resolved.infraBranch}, timeout=${resolved.timeout}m"

    return resolved
}

/**
 * Perform comprehensive Docker cleanup: stop and remove containers,
 * force-remove images, and remove named volumes.
 *
 * Each operation is wrapped in try/catch so that a single failure does not
 * prevent cleanup of other resources. This is safe to call in finally blocks
 * and post-failure stages.
 *
 * Parameters:
 *   containerNames (List<String>, optional) - Container names to stop + rm.
 *                                              Defaults to empty list.
 *   imageNames     (List<String>, optional) - Image names to rmi -f.
 *                                              Defaults to empty list.
 *   volumeNames    (List<String>, optional) - Volume names to rm.
 *                                              Defaults to empty list.
 *
 * Example:
 *   pipeline.standardDockerCleanup(
 *       containerNames: ['my-test', 'sidecar'],
 *       imageNames:     ['my-image:latest'],
 *       volumeNames:    ['my-data-vol']
 *   )
 */
def standardDockerCleanup(Map config = [:]) {
    def containerNames = config.containerNames ?: []
    def imageNames = config.imageNames ?: []
    def volumeNames = config.volumeNames ?: []

    steps.echo 'Running standard Docker cleanup'

    // Stop and remove containers
    containerNames.each { name ->
        try {
            steps.echo "Stopping container: ${name}"
            steps.sh "docker stop ${name} || true"
            steps.sh "docker rm -v ${name} || true"
        } catch (e) {
            steps.echo "Warning: Container cleanup failed for ${name}: ${e.message}"
        }
    }

    // Force-remove images
    imageNames.each { name ->
        try {
            steps.echo "Removing image: ${name}"
            steps.sh "docker rmi -f ${name} || true"
        } catch (e) {
            steps.echo "Warning: Image removal failed for ${name}: ${e.message}"
        }
    }

    // Remove volumes
    volumeNames.each { name ->
        try {
            steps.echo "Removing volume: ${name}"
            steps.sh "docker volume rm ${name} || true"
        } catch (e) {
            steps.echo "Warning: Volume removal failed for ${name}: ${e.message}"
        }
    }

    steps.echo 'Docker cleanup complete'
}

/**
 * Load a standard set of credentials based on the target environment and
 * execute the body closure with those credentials bound.
 *
 * Maps environment names to their corresponding Jenkins credential ID lists.
 * Additional credential IDs can be appended via the `additional` parameter.
 * Delegates to property.useWithCredentials() for actual credential binding.
 *
 * Supported environments and their credential sets:
 *   aws       - AWS_ACCESS_KEY_ID, AWS_SECRET_ACCESS_KEY
 *   azure     - AZURE_CLIENT_ID, AZURE_CLIENT_SECRET, AZURE_SUBSCRIPTION_ID, AZURE_TENANT_ID
 *   gcp       - GCP_SERVICE_ACCOUNT_KEY
 *   vsphere   - VSPHERE_USER, VSPHERE_PASSWORD, VSPHERE_SERVER
 *   harvester - HARVESTER_KUBECONFIG
 *   registry  - PRIVATE_REGISTRY_URL, PRIVATE_REGISTRY_USERNAME, PRIVATE_REGISTRY_PASSWORD
 *
 * Parameters:
 *   targetEnv  (String,        required) - Target environment identifier (aws, azure, gcp, vsphere, harvester, registry).
 *   additional (List<String>,  optional) - Extra credential IDs to include.
 *                                          Defaults to empty list.
 *   body       (Closure,       required) - Pipeline body to execute with credentials bound.
 *
 * Example:
 *   pipeline.standardCredentialLoader('aws') {
 *       tofu.apply(dir: 'infra/aws')
 *   }
 *
 *   pipeline.standardCredentialLoader('aws', ['MY_EXTRA_CRED']) {
 *       // AWS creds + MY_EXTRA_CRED available
 *   }
 */
def standardCredentialLoader(String targetEnv, List<String> additional = [], Closure body) {
    def credentialProfiles = [
        aws: [
            'AWS_ACCESS_KEY_ID',
            'AWS_SECRET_ACCESS_KEY',
            'AWS_SSH_PEM_KEY',
            'AWS_SSH_PEM_KEY_NAME'
        ],
        azure: [
            'AZURE_CLIENT_ID',
            'AZURE_CLIENT_SECRET',
            'AZURE_SUBSCRIPTION_ID',
            'AZURE_TENANT_ID'
        ],
        gcp: [
            'GCP_SERVICE_ACCOUNT_KEY'
        ],
        vsphere: [
            'VSPHERE_USER',
            'VSPHERE_PASSWORD',
            'VSPHERE_SERVER'
        ],
        harvester: [
            'HARVESTER_KUBECONFIG'
        ],
        registry: [
            'PRIVATE_REGISTRY_URL',
            'PRIVATE_REGISTRY_USERNAME',
            'PRIVATE_REGISTRY_PASSWORD'
        ]
    ]

    def normalizedEnv = targetEnv.toLowerCase()
    def baseCreds = credentialProfiles[normalizedEnv]

    if (!baseCreds) {
        error "Unknown target environment '${targetEnv}'. " +
              "Supported: ${credentialProfiles.keySet().join(', ')}"
    }

    def allCreds = baseCreds + additional

    steps.echo "Loading credentials for environment: ${normalizedEnv} (${allCreds.size()} credentials)"

    new property().useWithCredentials(allCreds, body)
}

// ---------------------------------------------------------------------------
// Pipeline templates
// ---------------------------------------------------------------------------

/**
 * Airgap infrastructure pipeline template.
 *
 * Orchestrates the full lifecycle: checkout → tofu provision → custom body →
 * teardown (always runs in finally block). The body closure receives the tofu
 * outputs as its parameter for further processing.
 *
 * This template ensures infrastructure is always torn down even when the body
 * fails, preventing orphaned cloud resources.
 *
 * Parameters:
 *   params        (Map,     required) - Pipeline parameters from resolvePipelineParams().
 *   workspaceName (String,  required) - Tofu workspace name.
 *   tofuConfig    (Map,     required) - Tofu configuration:
 *     dir               (String)  - Tofu config directory.
 *     backendInitScript (String)  - Backend init script path.
 *     bucket            (String)  - S3 state bucket.
 *     key               (String)  - S3 state key.
 *     region            (String)  - S3 region.
 *     varFile           (String)  - .tfvars file.
 *   destroyOnFailure (Boolean, optional) - Tear down on body failure.
 *                                           Defaults to true.
 *   body          (Closure, required) - Steps to run after provision.
 *                                      Receives a Map with 'outputs' key.
 *
 * Example:
 *   pipeline.airgapInfraPipeline(
 *       params: params,
 *       workspaceName: wsName,
 *       tofuConfig: [
 *           dir: 'infra/aws',
 *           backendInitScript: 'scripts/init-backend.sh',
 *           bucket: env.S3_BUCKET_NAME,
 *           key: "state/${wsName}/terraform.tfstate",
 *           region: 'us-east-1',
 *           varFile: 'terraform.tfvars'
 *       ]
 *   ) { data ->
 *       echo "Bastion: ${data.outputs.bastion_public_dns}"
 *   }
 */
def airgapInfraPipeline(Map config, Closure body) {
    if (!(config.params && config.workspaceName && config.tofuConfig)) {
        error 'params, workspaceName, and tofuConfig must be provided for airgapInfraPipeline.'
    }

    def tc = config.tofuConfig
    def wsName = config.workspaceName
    def destroyOnFailure = config.destroyOnFailure != false
    def outputs = [:]

    try {
        // 1. Check out repositories
        def ag = new airgap()
        def dirs = ag.standardCheckout(
            testsRepo: [branch: config.params.testsBranch],
            infraRepo: [branch: config.params.infraBranch]
        )

        // 2. Provision infrastructure
        def tf = new tofu()
        tf.initBackend(
            dir: tc.dir,
            backendInitScript: tc.backendInitScript,
            bucket: tc.bucket,
            key: tc.key,
            region: tc.region
        )
        tf.createWorkspace(dir: tc.dir, name: wsName)

        def infra = new infrastructure()
        infra.archiveWorkspaceName(workspaceName: wsName)

        tf.apply(dir: tc.dir, varFile: tc.varFile, autoApprove: true)

        // 3. Retrieve outputs
        outputs = tf.getOutputs(dir: tc.dir)

        // 4. Execute caller's body with outputs
        if (body) {
            body([outputs: outputs, dirs: dirs])
        }
    } catch (e) {
        steps.echo "Pipeline body failed: ${e.message}"

        if (destroyOnFailure) {
            steps.echo 'Destroying infrastructure due to failure'
            try {
                new airgap().teardownInfrastructure(
                    dir: config.tofuConfig.dir,
                    name: wsName,
                    varFile: config.tofuConfig.varFile
                )
            } catch (cleanupError) {
                steps.echo "Warning: Cleanup after failure also failed: ${cleanupError.message}"
            }
        }

        throw e
    }

    // 5. Teardown on success (if requested)
    if (config.destroyAfterSuccess) {
        steps.echo 'Destroying infrastructure after successful run'
        new airgap().teardownInfrastructure(
            dir: tc.dir,
            name: wsName,
            varFile: tc.varFile
        )
    }
}

/**
 * Airgap test pipeline template.
 *
 * Extends the airgap infrastructure pattern by adding container-based test
 * execution after infrastructure is provisioned. Handles:
 *   checkout → provision → prepare container → run tests → report → teardown
 *
 * Parameters:
 *   params          (Map, required) - Pipeline parameters from resolvePipelineParams().
 *   workspaceName   (String, required) - Tofu workspace name.
 *   tofuConfig      (Map, required) - Tofu configuration (see airgapInfraPipeline).
 *   containerConfig (Map, required) - Container configuration for test execution:
 *     workspace  (String) - Workspace name (e.g. env.WORKSPACE_NAME).
 *     dir        (String) - Subdirectory within workspace.
 *     name       (String) - Docker container name.
 *     image      (String) - Docker image to run.
 *   testConfig      (Map, required) - Test configuration:
 *     packages (String) - Go package pattern.
 *     cases    (String) - -run expression.
 *     tags     (String, optional) - Build tags.
 *     timeout  (String, optional) - Test timeout.
 *   destroyOnFailure (Boolean, optional) - Defaults to true.
 *   destroyAfterTests (Boolean, optional) - Defaults to false.
 *
 * Returns the test result Map from container.run().
 *
 * Example:
 *   pipeline.airgapTestPipeline(
 *       params: params,
 *       workspaceName: wsName,
 *       tofuConfig: [...],
 *       containerConfig: [workspace: wsName, dir: 'validation', name: names.container, image: names.image],
 *       testConfig: [packages: './validation/tests/...', cases: '-run TestAirgapSuite']
 *   )
 */
def airgapTestPipeline(Map config) {
    if (!(config.params && config.workspaceName && config.tofuConfig &&
          config.containerConfig && config.testConfig)) {
        error 'params, workspaceName, tofuConfig, containerConfig, and testConfig must be provided.'
    }

    def names = [
        container: config.containerConfig.name,
        image: config.containerConfig.image
    ]

    try {
        // Run inside the infra pipeline template (provision + body)
        airgapInfraPipeline(
            params: config.params,
            workspaceName: config.workspaceName,
            tofuConfig: config.tofuConfig,
            destroyOnFailure: config.destroyOnFailure
        ) { data ->
            // Prepare container environment
            def cont = new container()
            cont.prepare(
                workspace: config.containerConfig.workspace,
                dir: config.containerConfig.dir
            )

            // Run tests
            def result = cont.run(
                container: config.containerConfig,
                test: [params: config.testConfig]
            )

            // Report results
            new result().reportFromContainer(
                name: names.container,
                image: names.image,
                workspace: config.containerConfig.workspace,
                dir: config.containerConfig.dir,
                resultsXML: 'results.xml'
            )
        }
    } finally {
        // Always clean up container and infrastructure
        standardDockerCleanup(
            containerNames: [names.container],
            imageNames: [names.image]
        )

        if (config.destroyAfterTests) {
            try {
                new airgap().teardownInfrastructure(
                    dir: config.tofuConfig.dir,
                    name: config.workspaceName,
                    varFile: config.tofuConfig.varFile
                )
            } catch (e) {
                steps.echo "Warning: Infrastructure teardown failed: ${e.message}"
            }
        }
    }
}

/**
 * Simple test runner pipeline template.
 *
 * Provides the standard flow for test pipelines that do not manage
 * infrastructure: checkout → prepare → build → run → report → cleanup.
 *
 * Parameters:
 *   params          (Map, required) - Pipeline parameters from resolvePipelineParams().
 *   containerConfig (Map, required) - Container configuration (see container.run).
 *   testConfig      (Map, required) - Test configuration (see container.run).
 *   buildScript     (String, optional) - Build script path. Defaults to null (skip build).
 *   configureScript (String, optional) - Configure script path. Defaults to null (skip configure).
 *   dir             (String, optional) - Working directory. Defaults to '.'.
 *
 * Returns the test result Map from container.run().
 *
 * Example:
 *   pipeline.simpleTestPipeline(
 *       params: params,
 *       containerConfig: [workspace: wsName, dir: 'validation', name: names.container, image: names.image],
 *       testConfig: [packages: './tests/...', cases: '-run TestSuite'],
 *       buildScript: 'pipeline/scripts/build.sh',
 *       dir: 'validation'
 *   )
 */
def simpleTestPipeline(Map config) {
    if (!(config.params && config.containerConfig && config.testConfig)) {
        error 'params, containerConfig, and testConfig must be provided for simpleTestPipeline.'
    }

    def names = [
        container: config.containerConfig.name,
        image: config.containerConfig.image
    ]

    try {
        // 1. Check out the test repository
        def p = new project()
        p.checkout(
            repository: config.params.testsRepo,
            branch: config.params.testsBranch,
            target: config.dir ?: '.'
        )

        // 2. Prepare container environment
        def cont = new container()
        cont.prepare(
            workspace: config.containerConfig.workspace,
            dir: config.containerConfig.dir
        )

        // 3. Build (optional)
        if (config.buildScript) {
            cont.build(
                buildScript: config.buildScript,
                configureScript: config.configureScript,
                dir: config.dir
            )
        }

        // 4. Run tests
        def testResult = cont.run(
            container: config.containerConfig,
            test: [params: config.testConfig]
        )

        // 5. Report results
        new result().reportFromContainer(
            name: names.container,
            image: names.image,
            workspace: config.containerConfig.workspace,
            dir: config.containerConfig.dir,
            resultsXML: 'results.xml'
        )

        return testResult
    } finally {
        // 6. Always clean up
        standardDockerCleanup(
            containerNames: [names.container],
            imageNames: [names.image]
        )
    }
}
