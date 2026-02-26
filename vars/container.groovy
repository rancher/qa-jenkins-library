/**
 * container.groovy
 *
 * Docker container lifecycle management for Jenkins pipelines.
 *
 * Handles all stages of a test container's life:
 *   1. prepare()  - Write SSH keys and configuration files into the workspace
 *                   and set CATTLE_TEST_CONFIG so the test binary can find them.
 *   2. build()    - Run the optional configure script then the build script to
 *                   produce the test Docker image.
 *   3. run()      - Start the container, execute the test command (or a custom
 *                   command) inside it, and optionally publish Qase results.
 *   4. remove()   - Stop and remove one or more containers and their images.
 *
 * Private helpers (prefixed with _) build the docker run argument list,
 * construct the gotestsum invocation, and handle result publishing.
 *
 * Usage
 * -----
 *   def assetsDir = container.prepare(workspace: env.WORKSPACE_NAME, dir: 'validation')
 *
 *   container.build(buildScript: 'validation/pipeline/scripts/build.sh')
 *
 *   def result = container.run(
 *       container: [
 *           workspace: env.WORKSPACE_NAME,
 *           dir:       'validation',
 *           name:      names.container,
 *           image:     names.image
 *       ],
 *       test: [
 *           params: [
 *               packages: './validation/tests/...',
 *               cases:    '-run TestAirgapSuite',
 *               tags:     'validation'
 *           ]
 *       ]
 *   )
 *
 *   container.remove([[name: names.container, image: names.image]])
 */

/**
 * Write SSH keys and the test configuration file into the workspace, then set
 * CATTLE_TEST_CONFIG to the absolute in-container path of the config file.
 *
 * Reads the following environment variables:
 *   AWS_SSH_PEM_KEY_NAME / AWS_SSH_PEM_KEY - PEM private key filename and base64 content
 *   AWS_SSH_RSA_KEY_NAME / AWS_SSH_RSA_KEY - RSA private key filename and base64 content
 *   CONFIG_NAME / CONFIG                   - Test config filename and content
 *
 * Parameters:
 *   workspace (String, required) - Workspace name used to build the absolute
 *                                  in-container config path (/root/<workspace>/...).
 *   dir       (String, optional) - Subdirectory within the workspace to write
 *                                  assets into. Defaults to '.'.
 *
 * Returns the assets directory path (e.g. './validation').
 *
 * Example:
 *   def assetsDir = container.prepare(workspace: 'rancher_airgap_42', dir: 'validation')
 */
def prepare(Map params) {
    def workspace = params.workspace

    def dir = params.dir

    if (!dir) {
        dir = '.'
    }

    def assetsDir = "./${dir}"

    def sshDir = "${assetsDir}/.ssh"

    def awsPEM = [
        dir: sshDir,
        isBase64: true,
        fileName: env.AWS_SSH_PEM_KEY_NAME,
        content:  env.AWS_SSH_PEM_KEY
    ]

    def awsPEMPath = _newFile(awsPEM)

    def awsRSA = [
        dir: sshDir,
        isBase64: true,
        fileName: env.AWS_SSH_RSA_KEY_NAME,
        content:  env.AWS_SSH_RSA_KEY
    ]

    def awsRSAPath = _newFile(awsRSA)

    def configuration = [
        dir: assetsDir,
        fileName: env.CONFIG_NAME,
        content:  env.CONFIG
    ]

    def configurationPath = _newFile(configuration)

    env.CATTLE_TEST_CONFIG = "/root/${workspace}/${configurationPath}"

    return assetsDir
}

/**
 * Internal helper — write a single file to disk, optionally base64-decoding
 * the content first.
 *
 * Parameters:
 *   dir      (String,  required) - Directory to write the file into.
 *   fileName (String,  required) - Filename within the directory.
 *   content  (String,  required) - File content (plain text or base64).
 *   isBase64 (Boolean, optional) - Decode content from base64 before writing.
 *                                  Defaults to false.
 *
 * Returns the full relative path (dir/fileName).
 */
def _newFile(Map params = [:]) {
    def fullPath =  "${params.dir}/${params.fileName}"

    steps.echo "Creating new file ${fullPath}"

    steps.dir(params.dir) {
        def content = params?.isBase64 ? new String(params?.content.decodeBase64()) : params?.content

        try {
            steps.writeFile file: params.fileName, text: content
        } catch (e) {
            error "Error writing new file [${fullPath}]: ${e.message}"
        }
    }

    return fullPath
}

/**
 * Build the test Docker image by running one or more shell scripts.
 *
 * If configureScript is provided it runs first (e.g. to generate a Dockerfile
 * or prepare build context). The buildScript is always run and its exit code
 * determines success or failure. Both scripts are resolved relative to the
 * workspace root as `./<dir>/<script>`.
 *
 * Parameters:
 *   buildScript     (String, required) - Path (relative to workspace) of the
 *                                        image build script.
 *   configureScript (String, optional) - Path of a pre-build configure script.
 *   dir             (String, optional) - Directory prefix for script paths.
 *                                        Defaults to '.'.
 *
 * Example:
 *   container.build(
 *       dir:         'validation',
 *       buildScript: 'pipeline/scripts/build.sh'
 *   )
 */
def build(Map params) {
    steps.echo 'Configuring and building the container'

    if (!params.dir) {
        params.dir = '.'
    }

    if (params?.configureScript) {
        // by doing this we accept all build and configure going to run from dir, 'docker build .', currently target is in validation dir so this is necessary
        def statusConfigure = steps.sh(script: "./${params.dir}/${params?.configureScript}", returnStatus: true)

        if (statusConfigure != 0) {
            error "Build script failed with ${statusBuild}"
        }
    }

    def statusBuild = steps.sh(script: "./${params.dir}/${params.buildScript}", returnStatus: true)

    if (statusBuild != 0) {
        error "Build script failed with ${statusBuild}"
    }
}

/**
 * Run the test container, execute the test (or custom) command inside it,
 * and optionally trigger Qase result publishing.
 *
 * On any error (while building the command or during execution) the container
 * and its image are cleaned up via remove() before the exception is re-thrown.
 *
 * Parameters:
 *   container (Map, required) - Container configuration:
 *     workspace (String, required) - Workspace name (used for Qase publish path).
 *     dir       (String, required) - Subdirectory within the workspace.
 *     name      (String, required) - Docker container name (--name).
 *     image     (String, required) - Docker image to run.
 *     envFile   (String, optional) - Path to env-file. Defaults to config default.
 *     tty       (Boolean,optional) - Allocate a TTY (-t). Defaults to true.
 *   test (Map, required) - Test configuration — provide exactly one of:
 *     command (List<String>, optional) - Arbitrary command to run in the container
 *                                        (e.g. ['echo', 'hello']).
 *     params  (Map,          optional) - Go test parameters (see _goTestCommand).
 *
 * Returns a Map with keys 'container' and 'test' holding the resolved configs.
 *
 * Example:
 *   container.run(
 *       container: [workspace: 'ws_42', dir: 'validation', name: 'my-test', image: 'my-image:latest'],
 *       test: [params: [packages: './...', cases: '-run TestSuite']]
 *   )
 */
def run(Map config) {
    def statusRun

    def containerArgs = []
    def testArgs = []

    def containerConfig = [:]
    def testConfig = [:]

    try {
        (containerArgs, containerConfig) = _containerCommand(config?.container)

        (testArgs, testConfig) = _testCommand(config?.test)
    } catch (e) {
        def errorMessage = "Error building run command: ${e.message}"

        remove([ [name: config.container.name, image: config.container.image] ])

        throw new Exception(errorMessage)
    }

    def publishArgs = _publishFromContainer( [workspace: config?.container.workspace, dir: config?.container.dir] )

    def testCommand = testArgs.join(' ')

    testCommand += publishArgs

    def fullCommand = containerArgs + [ testCommand ]

    try {
        steps.sh(script: fullCommand.join(' '))
    } catch (e) {
        def errorMessage = "Error executing run command: ${e.message}"

        remove([ [name: config.container.name, image: config.container.image] ])

        throw new Exception(errorMessage)
    }

    return [container: containerConfig, test: testConfig ]
}

/**
 * Internal helper — build the `docker run` argument list from a container
 * configuration map.
 *
 * Applies defaults from the 'docker' config section for the env-file when
 * none is supplied by the caller.
 *
 * Parameters:
 *   container (Map, required):
 *     name    (String,  required) - Container name passed to --name.
 *     image   (String,  required) - Image to run.
 *     envFile (String,  optional) - Env-file path (--env-file). Defaults to config default.
 *     tty     (Boolean, optional) - Add -t flag. Defaults to true.
 *
 * Returns [args (List<String>), container (Map)].
 */
def _containerCommand(Map container) {
    def args =  ['docker', 'run']

    if ( !(container.name && container.image) ) {
        error "Container image must be specified in the 'container' configuration."

        return error
    }

    if (container.name) {
        args.addAll(['--name', container.name])
    }

    if ( !(container?.envFile) ) {
        def globalConfig = new config()
        def dockerConfig = globalConfig.getConfig('docker')
        container.envFile = dockerConfig.defaultEnvFile
    }

    args.addAll(['--env-file', container.envFile])

    if (container?.tty != false) {
        args.add('-t')
    }

    if (container.image) {
        args.add(container.image)
    }

    return [args, container]
}

/**
 * Internal helper — build the Qase reporter publish command fragment to
 * append to the test command string.
 *
 * When QASE_TEST_RUN_ID is not set returns an empty string so that the
 * publish step is skipped silently.
 *
 * Parameters:
 *   workspace (String, required) - Workspace name for constructing the
 *                                  in-container script paths.
 *   dir       (String, optional) - Subdirectory. Defaults to '.'.
 *
 * Returns a shell command fragment string (may be empty).
 */
def _publishFromContainer(Map containerParams) {
    if (!containerParams.dir) {
        containerParams.dir = '.'
    }

    def publishCommand = ''

    if (env.QASE_TEST_RUN_ID) {
        def builderPath =  "/root/${containerParams.workspace}/${containerParams.dir}/pipeline/scripts/build_qase_reporter.sh;"

        def executablePath =  "/root/${containerParams.workspace}/${containerParams.dir}/reporter;"

        publishCommand =  _wrapInDoubleQuotes(builderPath) + _wrapInDoubleQuotes(executablePath)
    }

    return publishCommand
}

/**
 * Internal helper — build the full test command argument list from the test
 * configuration map.
 *
 * Exactly one of command or params must be provided. Providing both or
 * neither is an error.
 *
 * Parameters:
 *   test (Map, required) — provide exactly one of:
 *     command (List<String>) - Raw command tokens passed directly to sh -c.
 *     params  (Map)          - Go test parameters forwarded to _goTestCommand.
 *
 * Returns [args (List<String>), testConfig (Map)].
 */
def _testCommand(Map test) {
    if ( !(test.command || test.params) || (test.command && test.params) ) {
        error "Either 'Command' or 'Test' must be specified in test configuration."

        return error
    }

    def args = ['sh', '-c']
    def testArgs = []
    def testParams = [:]

    if (test?.params) {
        (testArgs, testParams) = _goTestCommand(test.params)
    }

    if (test?.command) {
        testArgs = args.addAll(test.command)
    }

    args << _wrapInDoubleQuotes(testArgs.join(' '))

    return [args, testParams]
}

/**
 * Internal helper — wrap a string in double quotes for shell embedding.
 *
 * Parameters:
 *   s (String, required) - The string to wrap.
 *
 * Returns the string surrounded by double-quote characters.
 */
def _wrapInDoubleQuotes(String s) {
    return "\"${s}\""
}

/**
 * Internal helper — build the gotestsum argument list for a Go test run.
 *
 * Missing optional values are filled from the 'testing' configuration section
 * so that callers only need to supply the test-specific packages and cases.
 *
 * Parameters:
 *   test (Map, required):
 *     packages   (String, required) - Go package pattern passed to --packages
 *                                     (e.g. './validation/tests/...').
 *     cases      (String, required) - -run expression (e.g. '-run TestAirgapSuite').
 *     resultsXML (String, optional) - JUnit XML output path. Defaults to config default.
 *     resultsJSON(String, optional) - JSON output path. Defaults to config default.
 *     tags       (String, optional) - Go build tags (-tags). Defaults to config default.
 *     timeout    (String, optional) - Test timeout (-timeout). Defaults to config default.
 *
 * Returns [args (List<String>), test (Map)] where test is the resolved config.
 */
def _goTestCommand(Map test) {
    if (! (test.packages && test.cases) ) {
        error "Both 'packages' and 'cases' must be specified in test configuration."

        return error
    }

    def args = ['gotestsum', '--format', 'standard-verbose' ]

    args.add("--packages=${test.packages}")

    if (!(test?.resultsXML)) {
        def globalConfig = new config()
        def testConfig = globalConfig.getConfig('testing')
        test.resultsXML = testConfig.defaultResultsXML
    }

    args.addAll(['--junitfile', test.resultsXML])

    if (!(test?.resultsJSON)) {
        def globalConfig = new config()
        def testConfig = globalConfig.getConfig('testing')
        test.resultsJSON = testConfig.defaultResultsJSON
    }

    args.addAll(['--jsonfile', test.resultsJSON])

    args.add('--')

    if (!(test?.tags)) {
        def globalConfig = new config()
        def testConfig = globalConfig.getConfig('testing')
        test.tags = testConfig.defaultTags
    }

    args.add("-tags=${test.tags}")

    args.add(test.cases)

    if (!(test?.timeout)) {
        def globalConfig = new config()
        def testConfig = globalConfig.getConfig('testing')
        test.timeout = testConfig.defaultTimeout
    }

    args.add("-timeout=${test.timeout}")

    args.add('-v;')

    return [args, test]
}

/**
 * Stop and remove one or more Docker containers and optionally their images.
 *
 * Each entry in the list is processed independently; errors for a single
 * container are logged but do not prevent the remaining entries from being
 * cleaned up.
 *
 * Parameters:
 *   containers (List<Map>, required) - List of maps, each with:
 *     name  (String, required) - Container name to stop and remove.
 *     image (String, optional) - Image name to force-remove (docker rmi -f).
 *                                Omit to skip image removal.
 *
 * Example:
 *   container.remove([
 *       [name: 'my-test-container', image: 'my-test-image:latest'],
 *       [name: 'sidecar-container']
 *   ])
 */
def remove(List<Map<String, Object>> containers) {
    containers.each { container ->
        def name = container.name
        def image = container?.image

        try {
            steps.echo "Removing container name [${name}]"

            steps.sh "docker stop ${name}"

            steps.sh "docker rm -v ${name}"
        } catch (e) {
            steps.echo "Container name [${name}] removal failed: ${e.message}"
        }

        if (image) {
            try {
                steps.echo "Removing container image [${image}]"

                steps.sh "docker rmi -f ${image}"
            } catch (e) {
                steps.echo "Container image [${image}] removal failed: ${e.message}"
            }
        }
    }
}
