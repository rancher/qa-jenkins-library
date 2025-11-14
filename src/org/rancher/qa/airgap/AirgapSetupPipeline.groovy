package org.rancher.qa.airgap

/**
 * Shared-library implementation of the airgap setup pipeline.
 * Encapsulates the logic that previously lived in Jenkinsfile.setup.airgap.rke2
 * so Jenkins can delegate orchestration to a reusable helper.
 */
class AirgapSetupPipeline implements Serializable {

    private static final long serialVersionUID = 1L

    private static final List<String> DEFAULT_ARTIFACT_PATTERNS = [
        'artifacts/**',
        'infrastructure-outputs.json',
        'ansible-inventory.yml',
        'kubeconfig.yaml',
        'deployment-summary.json'
    ]

    private final def steps
    private final EnvironmentManager envManager
    private final DockerManager dockerManager
    private final InfrastructureManager infraManager
    private final ArtifactManager artifactManager
    private final ValidationManager validationManager

    private Map state = [:]

    AirgapSetupPipeline(def steps) {
        this.steps = steps
        this.envManager = new EnvironmentManager(steps)
        this.dockerManager = new DockerManager(steps)
        this.infraManager = new InfrastructureManager(dockerManager)
        this.artifactManager = new ArtifactManager(steps)
        this.validationManager = new ValidationManager(steps)
    }

    Map initialize(Map ctx = [:]) {
        logInfo('Initializing setup pipeline')
        steps.deleteDir()
        state = ctxWithDefaults(ctx)
        envManager.configureSetupEnvironment(state)
        syncEnvFromContext([
            'BUILD_CONTAINER_NAME',
            'IMAGE_NAME',
            'VALIDATION_VOLUME',
            'TF_WORKSPACE',
            'QA_INFRA_WORK_PATH',
            'TERRAFORM_VARS_FILENAME',
            'ANSIBLE_VARS_FILENAME',
            'TERRAFORM_BACKEND_CONFIG_FILENAME',
            'ENV_FILE',
            'RKE2_VERSION',
            'RANCHER_VERSION',
            'HOSTNAME_PREFIX',
            'RANCHER_HOSTNAME',
            'S3_BUCKET_NAME',
            'S3_BUCKET_REGION',
            'S3_KEY_PREFIX',
            'AWS_REGION',
            'DESTROY_ON_FAILURE'
        ])
        validationManager.validateSensitiveDataHandling(state, false)
        state.CONTAINER_PREPARED = false
        logInfo("Build container: ${state.BUILD_CONTAINER_NAME}")
        logInfo("Docker image: ${state.IMAGE_NAME}")
        logInfo("Validation volume: ${state.VALIDATION_VOLUME}")
        return state
    }

    void checkoutRepositories() {
        ensureState()
        logInfo('Checking out repositories')
        def checkoutExtensions = [
            [$class: 'CleanCheckout'],
            [$class: 'CloneOption', depth: 1, shallow: true]
        ]

        steps.dir('./tests') {
            steps.checkout([
                $class: 'GitSCM',
                branches: [[name: "*/${state.RANCHER_TEST_REPO_BRANCH}" ]],
                extensions: checkoutExtensions,
                userRemoteConfigs: [[url: state.RANCHER_TEST_REPO_URL]]
            ])
        }

        steps.dir('./qa-infra-automation') {
            steps.checkout([
                $class: 'GitSCM',
                branches: [[name: "*/${state.QA_INFRA_REPO_BRANCH}" ]],
                extensions: checkoutExtensions,
                userRemoteConfigs: [[url: state.QA_INFRA_REPO_URL]]
            ])
            try {
                def branch = steps.sh(script: 'git rev-parse --abbrev-ref HEAD', returnStdout: true).trim()
                def commit = steps.sh(script: 'git log -1 --oneline', returnStdout: true).trim()
                logInfo("qa-infra-automation branch: ${branch}")
                logInfo("qa-infra-automation commit: ${commit}")
            } catch (Exception ignored) {
                logWarning('Unable to determine QA infra branch/commit')
            }
        }
    }

    void configureEnvironment() {
        ensureState()
        validationManager.validatePipelineParameters(state)
        validationManager.validateSensitiveDataHandling(state, false)
    }

    void prepareInfrastructure() {
        ensureState()
        prepareContainerResources()
        validationManager.validatePipelineParameters(state)
        validationManager.validateSensitiveDataHandling(state, false)
    }

    void deployInfrastructure() {
        ensureState()
        prepareContainerResources()
        validationManager.ensureRequiredVariables(state, [
            'QA_INFRA_WORK_PATH',
            'TF_WORKSPACE',
            'TERRAFORM_VARS_FILENAME',
            'TERRAFORM_BACKEND_CONFIG_FILENAME'
        ])

        generateTofuConfiguration()

        infraManager.deployInfrastructure([
            timeout : state.TERRAFORM_TIMEOUT ?: PipelineDefaults.TERRAFORM_TIMEOUT_MINUTES,
            extraEnv: makeInfrastructureEnv()
        ])

        extractArtifactsFromVolume()
    }

    void prepareAnsibleEnvironment() {
        ensureState()
        prepareContainerResources()
        validationManager.ensureRequiredVariables(state, ['QA_INFRA_WORK_PATH', 'ANSIBLE_VARS_FILENAME'])

        infraManager.prepareAnsible([
            timeout : state.ANSIBLE_TIMEOUT ?: PipelineDefaults.ANSIBLE_TIMEOUT_MINUTES,
            extraEnv: makeAnsibleEnv()
        ])
    }

    void deployRke2() {
        ensureState()
        prepareContainerResources()
        validationManager.ensureRequiredVariables(state, ['QA_INFRA_WORK_PATH', 'ANSIBLE_VARS_FILENAME'])

        infraManager.deployRke2([
            timeout : state.ANSIBLE_TIMEOUT ?: PipelineDefaults.ANSIBLE_TIMEOUT_MINUTES,
            extraEnv: makeRke2Env()
        ])
    }

    void deployRancher() {
        ensureState()
        prepareContainerResources()
        validationManager.ensureRequiredVariables(state, ['QA_INFRA_WORK_PATH', 'ANSIBLE_VARS_FILENAME'])

        infraManager.deployRancher([
            timeout : state.ANSIBLE_TIMEOUT ?: PipelineDefaults.ANSIBLE_TIMEOUT_MINUTES,
            extraEnv: makeRancherEnv()
        ])
    }

    void handleFailureCleanup(String failureType) {
        ensureState()
        logError("${failureType} failure detected - initiating cleanup")

        try {
            safeExtractArtifacts()
            archiveFailureArtifacts(failureType)

            if (isDestroyOnFailureEnabled()) {
                logInfo('DESTROY_ON_FAILURE is true - executing cleanup script')
                runCleanupScript(reasonForFailure(failureType))
            } else {
                logWarning('DESTROY_ON_FAILURE is false - manual cleanup may be required')
            }
        } catch (Exception cleanupError) {
            logError("Failure cleanup encountered issues: ${cleanupError.message}")
        } finally {
            cleanupResources()
        }
    }

    void cleanupResources() {
        ensureState()
        if (state.CLEANUP_COMPLETED) {
            return
        }
        artifactManager.extractFromVolume(state.VALIDATION_VOLUME)
        artifactManager.archiveArtifacts(state.artifactPatterns ?: DEFAULT_ARTIFACT_PATTERNS)
        dockerManager.cleanupResources(state.IMAGE_NAME, state.VALIDATION_VOLUME, state.BUILD_CONTAINER_NAME)
        envManager.cleanupSSHKeys()
        shredEnvFile()
        state.CLEANUP_COMPLETED = true
    }

    void archiveArtifacts(List patterns = null) {
        ensureState()
        artifactManager.archiveArtifacts(patterns ?: state.artifactPatterns ?: DEFAULT_ARTIFACT_PATTERNS)
    }

    void archiveFailureArtifacts(String failureType) {
        ensureState()
        artifactManager.archiveArtifacts(failureArtifactPatterns(failureType))
    }

    Map getState() {
        state
    }

    private void ensureState() {
        if (!state) {
            steps.error('Airgap setup pipeline state has not been initialized')
        }
    }

    private Map ctxWithDefaults(Map ctx) {
        ctx = new LinkedHashMap(ctx ?: [:])
        def jobSuffix = computeJobSuffix()

        ctx.BUILD_CONTAINER_NAME = ctx.BUILD_CONTAINER_NAME ?: "${PipelineDefaults.CONTAINER_NAME_PREFIX}-${jobSuffix}"
        ctx.IMAGE_NAME = ctx.IMAGE_NAME ?: "rancher-ansible-airgap-setup-${jobSuffix}"
        ctx.VALIDATION_VOLUME = ctx.VALIDATION_VOLUME ?: "${PipelineDefaults.SHARED_VOLUME_PREFIX}-${jobSuffix}"
        ctx.ENV_FILE = ctx.ENV_FILE ?: '.env'
        ctx.ANSIBLE_VARS_FILENAME = ctx.ANSIBLE_VARS_FILENAME ?: 'vars.yaml'
        ctx.TERRAFORM_VARS_FILENAME = ctx.TERRAFORM_VARS_FILENAME ?: 'cluster.tfvars'
        ctx.TERRAFORM_BACKEND_CONFIG_FILENAME = ctx.TERRAFORM_BACKEND_CONFIG_FILENAME ?: 'backend.tf'
        ctx.QA_INFRA_WORK_PATH = ctx.QA_INFRA_WORK_PATH ?: '/root/go/src/github.com/rancher/qa-infra-automation'
        ctx.TF_WORKSPACE = ctx.TF_WORKSPACE ?: "jenkins_airgap_ansible_workspace_${steps.env.BUILD_NUMBER ?: '0'}"

        ctx.RANCHER_TEST_REPO_URL = ctx.RANCHER_TEST_REPO_URL ?: PipelineDefaults.DEFAULT_RANCHER_TEST_REPO
        ctx.RANCHER_TEST_REPO_BRANCH = ctx.RANCHER_TEST_REPO_BRANCH ?: 'main'
        ctx.QA_INFRA_REPO_URL = ctx.QA_INFRA_REPO_URL ?: PipelineDefaults.DEFAULT_QA_INFRA_REPO
        ctx.QA_INFRA_REPO_BRANCH = ctx.QA_INFRA_REPO_BRANCH ?: 'main'

        ctx.S3_BUCKET_NAME = ctx.S3_BUCKET_NAME ?: PipelineDefaults.DEFAULT_S3_BUCKET
        ctx.S3_BUCKET_REGION = ctx.S3_BUCKET_REGION ?: PipelineDefaults.DEFAULT_S3_BUCKET_REGION
        ctx.S3_KEY_PREFIX = ctx.S3_KEY_PREFIX ?: 'jenkins-airgap-rke2'
        ctx.AWS_REGION = ctx.AWS_REGION ?: ctx.S3_BUCKET_REGION

        ctx.RKE2_VERSION = ctx.RKE2_VERSION ?: PipelineDefaults.DEFAULT_RKE2_VERSION
        ctx.RANCHER_VERSION = ctx.RANCHER_VERSION ?: PipelineDefaults.DEFAULT_RANCHER_VERSION
        ctx.HOSTNAME_PREFIX = ctx.HOSTNAME_PREFIX ?: PipelineDefaults.DEFAULT_HOSTNAME_PREFIX
        ctx.RANCHER_HOSTNAME = ctx.RANCHER_HOSTNAME ?: "${ctx.HOSTNAME_PREFIX}.qa.rancher.space"

        ctx.PRIVATE_REGISTRY_URL = ctx.PRIVATE_REGISTRY_URL ?: ''
        ctx.PRIVATE_REGISTRY_USERNAME = ctx.PRIVATE_REGISTRY_USERNAME ?: 'default-user'
        ctx.PRIVATE_REGISTRY_PASSWORD = ctx.PRIVATE_REGISTRY_PASSWORD ?: ''

        ctx.TERRAFORM_TIMEOUT = ctx.TERRAFORM_TIMEOUT ?: PipelineDefaults.TERRAFORM_TIMEOUT_MINUTES
        ctx.ANSIBLE_TIMEOUT = ctx.ANSIBLE_TIMEOUT ?: PipelineDefaults.ANSIBLE_TIMEOUT_MINUTES
        ctx.VALIDATION_TIMEOUT = ctx.VALIDATION_TIMEOUT ?: PipelineDefaults.VALIDATION_TIMEOUT_MINUTES

        ctx.TERRAFORM_CONFIG = ctx.TERRAFORM_CONFIG ?: ''
        ctx.ANSIBLE_VARIABLES = ctx.ANSIBLE_VARIABLES ?: ''
        ctx.DESTROY_ON_FAILURE = normalizeBoolean(ctx.DESTROY_ON_FAILURE)

        ctx.artifactPatterns = ctx.artifactPatterns ?: DEFAULT_ARTIFACT_PATTERNS

        return ctx
    }

    private void prepareContainerResources() {
        ensureState()
        if (state.CONTAINER_PREPARED) {
            return
        }

        logInfo('Preparing container resources for setup workflow')
        dockerManager.buildImage(state.IMAGE_NAME)
        dockerManager.createSharedVolume(state.VALIDATION_VOLUME)

        steps.withCredentials(defaultCredentialBindings()) {
            dockerManager.stageSshKeys(state.VALIDATION_VOLUME)
        }

        state.CONTAINER_PREPARED = true
        logInfo('Container resources ready')
    }

    private Map makeInfrastructureEnv() {
        [
            'RKE2_VERSION'           : state.RKE2_VERSION,
            'RANCHER_VERSION'        : state.RANCHER_VERSION,
            'HOSTNAME_PREFIX'        : state.HOSTNAME_PREFIX,
            'RANCHER_HOSTNAME'       : state.RANCHER_HOSTNAME,
            'PRIVATE_REGISTRY_URL'   : state.PRIVATE_REGISTRY_URL,
            'PRIVATE_REGISTRY_USERNAME': state.PRIVATE_REGISTRY_USERNAME,
            'PRIVATE_REGISTRY_PASSWORD': state.PRIVATE_REGISTRY_PASSWORD,
            'UPLOAD_CONFIG_TO_S3'    : 'true',
            'S3_BUCKET_NAME'         : state.S3_BUCKET_NAME,
            'S3_BUCKET_REGION'       : state.S3_BUCKET_REGION,
            'S3_KEY_PREFIX'          : state.S3_KEY_PREFIX,
            'AWS_REGION'             : state.AWS_REGION,
            'AWS_SSH_KEY_NAME'       : steps.env.AWS_SSH_KEY_NAME,
            'ANSIBLE_VARIABLES'      : state.ANSIBLE_VARIABLES
        ].findAll { it.value != null }
    }

    private Map makeAnsibleEnv() {
        [
            'ANSIBLE_VARIABLES'       : state.ANSIBLE_VARIABLES,
            'RKE2_VERSION'            : state.RKE2_VERSION,
            'RANCHER_VERSION'         : state.RANCHER_VERSION,
            'HOSTNAME_PREFIX'         : state.HOSTNAME_PREFIX,
            'RANCHER_HOSTNAME'        : state.RANCHER_HOSTNAME,
            'PRIVATE_REGISTRY_URL'    : state.PRIVATE_REGISTRY_URL,
            'PRIVATE_REGISTRY_USERNAME': state.PRIVATE_REGISTRY_USERNAME,
            'PRIVATE_REGISTRY_PASSWORD': state.PRIVATE_REGISTRY_PASSWORD,
            'SKIP_YAML_VALIDATION'    : state.SKIP_YAML_VALIDATION ?: 'false',
            'AWS_SSH_KEY_NAME'        : steps.env.AWS_SSH_KEY_NAME
        ].findAll { it.value != null }
    }

    private Map makeRke2Env() {
        [
            'RKE2_VERSION'      : state.RKE2_VERSION,
            'SKIP_VALIDATION'   : 'false',
            'AWS_SSH_KEY_NAME'  : steps.env.AWS_SSH_KEY_NAME
        ].findAll { it.value != null }
    }

    private Map makeRancherEnv() {
        [
            'RANCHER_VERSION': state.RANCHER_VERSION,
            'HOSTNAME_PREFIX': state.HOSTNAME_PREFIX,
            'RANCHER_HOSTNAME': state.RANCHER_HOSTNAME,
            'SKIP_VERIFICATION': 'false'
        ].findAll { it.value != null }
    }

    private void generateTofuConfiguration() {
        logInfo('Generating Terraform configuration')
        if (!state.S3_BUCKET_NAME) {
            steps.error('S3_BUCKET_NAME is required to generate Terraform configuration')
        }
        if (!state.S3_BUCKET_REGION) {
            steps.error('S3_BUCKET_REGION is required to generate Terraform configuration')
        }
        if (!state.S3_KEY_PREFIX) {
            steps.error('S3_KEY_PREFIX is required to generate Terraform configuration')
        }

        steps.sh 'mkdir -p qa-infra-automation/tofu/aws/modules/airgap'

        if (!state.TERRAFORM_CONFIG?.trim()) {
            steps.error('TERRAFORM_CONFIG parameter is required for deployment')
        }

        def terraformConfig = state.TERRAFORM_CONFIG
        terraformConfig = terraformConfig.replace('${AWS_SECRET_ACCESS_KEY}', steps.env.AWS_SECRET_ACCESS_KEY ?: '')
        terraformConfig = terraformConfig.replace('${AWS_ACCESS_KEY_ID}', steps.env.AWS_ACCESS_KEY_ID ?: '')
        terraformConfig = terraformConfig.replace('${HOSTNAME_PREFIX}', state.HOSTNAME_PREFIX ?: '')

        steps.dir('qa-infra-automation/tofu/aws/modules/airgap') {
            steps.writeFile file: state.TERRAFORM_VARS_FILENAME, text: terraformConfig
            logInfo("Terraform variables written to ${state.TERRAFORM_VARS_FILENAME}")

            def backendConfig = """
terraform {
  backend \"s3\" {
    bucket = \"${state.S3_BUCKET_NAME}\"
    key    = \"${state.S3_KEY_PREFIX}\"
    region = \"${state.S3_BUCKET_REGION}\"
  }
}
""".stripIndent()
            steps.writeFile file: state.TERRAFORM_BACKEND_CONFIG_FILENAME, text: backendConfig
            logInfo("Terraform backend config written to ${state.TERRAFORM_BACKEND_CONFIG_FILENAME}")
        }
    }

    private void extractArtifactsFromVolume() {
        artifactManager.extractFromVolume(state.VALIDATION_VOLUME)
        generateDeploymentSummary()
    }

    private void safeExtractArtifacts() {
        try {
            extractArtifactsFromVolume()
        } catch (Exception ignored) {
            logWarning('Artifact extraction failed during failure cleanup')
        }
    }

    private void generateDeploymentSummary() {
        try {
            def artifactsDir = 'artifacts'
            steps.sh "mkdir -p ${artifactsDir}"
            def summary = [
                deployment_info: [
                    timestamp      : new Date().format('yyyy-MM-dd HH:mm:ss'),
                    build_number   : steps.env.BUILD_NUMBER,
                    job_name       : steps.env.JOB_NAME,
                    workspace      : state.TF_WORKSPACE,
                    rke2_version   : state.RKE2_VERSION,
                    rancher_version: state.RANCHER_VERSION,
                    rancher_hostname: state.RANCHER_HOSTNAME
                ],
                infrastructure: [
                    terraform_vars_file: state.TERRAFORM_VARS_FILENAME,
                    s3_bucket           : state.S3_BUCKET_NAME,
                    s3_bucket_region    : state.S3_BUCKET_REGION,
                    hostname_prefix     : state.HOSTNAME_PREFIX
                ]
            ]
            def summaryJson = groovy.json.JsonOutput.prettyPrint(groovy.json.JsonOutput.toJson(summary))
            steps.writeFile file: 'artifacts/deployment-summary.json', text: summaryJson
        } catch (Exception e) {
            logWarning("Unable to generate deployment summary: ${e.message}")
        }
    }

    private void shredEnvFile() {
        try {
            if (steps.fileExists(state.ENV_FILE)) {
                steps.sh "shred -vfz -n 3 ${state.ENV_FILE} 2>/dev/null || rm -f ${state.ENV_FILE}"
                logInfo('Environment file securely shredded')
            }
        } catch (Exception ignored) {
            logWarning('Failed to shred environment file')
        }
    }

    private void runCleanupScript(String reason) {
        def script = """#!/bin/bash
set -e
source /root/go/src/github.com/rancher/tests/validation/pipeline/scripts/airgap/airgap_cleanup.sh
perform_cleanup \"${reason}\" \"${state.TF_WORKSPACE}\" \"true\"
""".stripIndent()

        def extraEnv = [
            'QA_INFRA_WORK_PATH': state.QA_INFRA_WORK_PATH,
            'TF_WORKSPACE'      : state.TF_WORKSPACE,
            'TERRAFORM_BACKEND_CONFIG_FILENAME': state.TERRAFORM_BACKEND_CONFIG_FILENAME,
            'TERRAFORM_VARS_FILENAME'          : state.TERRAFORM_VARS_FILENAME
        ]

        dockerManager.executeScriptInContainer([
            script : script,
            timeout: state.TERRAFORM_TIMEOUT ?: PipelineDefaults.TERRAFORM_TIMEOUT_MINUTES,
            extraEnv: extraEnv
        ])
    }

    private boolean isDestroyOnFailureEnabled() {
        (state.DESTROY_ON_FAILURE ?: 'false').toString().equalsIgnoreCase('true')
    }

    private static List<String> failureArtifactPatterns(String failureType) {
        switch (failureType) {
            case 'deployment':
                return ['artifacts/**', 'terraform.tfstate', 'infrastructure-outputs.json']
            case 'ansible_prep':
                return ['artifacts/**', 'ansible-inventory.yml']
            case 'rke2':
            case 'rancher':
                return ['artifacts/**', 'kubeconfig.yaml']
            default:
                return DEFAULT_ARTIFACT_PATTERNS
        }
    }

    private static String reasonForFailure(String failureType) {
        switch (failureType) {
            case 'deployment':
                return 'deployment_failure'
            case 'ansible_prep':
                return 'ansible_prepare_failure'
            case 'rke2':
                return 'rke2_failure'
            case 'rancher':
                return 'rancher_failure'
            case 'timeout':
                return 'timeout'
            default:
                return failureType ?: 'deployment_failure'
        }
    }

    private List defaultCredentialBindings() {
        [
            steps.string(credentialsId: 'AWS_ACCESS_KEY_ID', variable: 'AWS_ACCESS_KEY_ID'),
            steps.string(credentialsId: 'AWS_SECRET_ACCESS_KEY', variable: 'AWS_SECRET_ACCESS_KEY'),
            steps.string(credentialsId: 'AWS_SSH_PEM_KEY', variable: 'AWS_SSH_PEM_KEY'),
            steps.string(credentialsId: 'AWS_SSH_KEY_NAME', variable: 'AWS_SSH_KEY_NAME')
        ]
    }

    private void syncEnvFromContext(List<String> keys) {
        // Use explicit this.state to avoid Groovy closure delegate shadowing inside Jenkins sandbox
        keys.each { key ->
            def value = this.state[key]
            if (value != null) {
                steps.env."${key}" = value.toString()
            }
        }
    }

    private String computeJobSuffix() {
        def jobName = steps.env.JOB_NAME ?: 'job'
        if (jobName.contains('/')) {
            jobName = jobName.tokenize('/')[-1]
        }
        "${jobName}${steps.env.BUILD_NUMBER ?: '0'}"
    }

    private static String normalizeBoolean(def value) {
        if (value == null) {
            return 'false'
        }
        def str = value.toString().trim()
        if (!str) {
            return 'false'
        }
        str.equalsIgnoreCase('true') ? 'true' : 'false'
    }

    private void logInfo(String msg) {
        steps.echo "${PipelineDefaults.LOG_PREFIX_INFO} ${timestamp()} ${msg}"
    }

    private void logWarning(String msg) {
        steps.echo "${PipelineDefaults.LOG_PREFIX_WARNING} ${timestamp()} ${msg}"
    }

    private void logError(String msg) {
        steps.echo "${PipelineDefaults.LOG_PREFIX_ERROR} ${timestamp()} ${msg}"
    }

    private static String timestamp() {
        new Date().format('yyyy-MM-dd HH:mm:ss')
    }

}
