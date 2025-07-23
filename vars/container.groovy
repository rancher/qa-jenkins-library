// [  workspace: string, dir?: string ]
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

// [ fileName: string, content: string, dir: string, isBase64?: bool ]
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

// [  buildScript: string, configureScript?: string, dir?: string ]
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

// [
//      container: [  workspace: string, dir: string, name: string, image: string, envFile?: string, tty?: bool],
//      test:      [
//                      command?: ["echo", "me"],
//                      params?: [ packages: string, cases: string, resultXML?: string, resultJSON?: string, tags?: string, timeout?: string ]
//                 ]
// ]
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

// [ name: string, image: string, envFile?: string, tty?: bool ]
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
        container.envFile = '.env'
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

// [workspace: string, dir: string]
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

// [
//   either command or test, not both of them:
//   command?: ["echo", "me" ],
//   params?: [ packages: string, cases: string, resultXML?: string, resultJSON?: string, tags?: string, timeout?: string ]
// ]
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

// string
def _wrapInDoubleQuotes(String s) {
    return "\"${s}\""
}

// [ packages: string, cases: string, resultXML?: string, resultJSON?: string, tags?: string, timeout?: string ]
def _goTestCommand(Map test) {
    if (! (test.packages && test.cases) ) {
        error "Both 'packages' and 'cases' must be specified in test configuration."

        return error
    }

    def args = ['/root/go/bin/gotestsum', '--format', 'standard-verbose' ]

    args.add("--packages=${test.packages}")

    if (!(test?.resultsXML)) {
        test.resultsXML = 'results.xml'
    }

    args.addAll(['--junitfile', test.resultsXML])

    if (!(test?.resultsJSON)) {
        test.resultsJSON = 'results.json'
    }

    args.addAll(['--jsonfile', test.resultsJSON])

    args.add('--')

    if (!(test?.tags)) {
        test.tags = 'validation'
    }

    args.add("-tags=${test.tags}")

    args.add(test.cases)

    if (!(test?.timeout)) {
        test.timeout = '60m'
    }

    args.add("-timeout=${test.timeout}")

    args.add('-v;')

    return [args, test]
}

// [ [name: string, image: string], [name: string] ]
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
