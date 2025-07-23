// until best practices
def _importFileContainer() {
    def c = new container()

    return c
}

// This is dockerfiles responsibility, lower chance option with tail or docker start option for the first container

// [name: string, image: string, workspace: string, dir: string]
// def publishFromContainer(Map containerParams) {
//     if (! (containerParams.name && containerParams.image))  {
//         error 'Container name and image must be provided.'
//     }
//
//     if (!containerParams.dir) {
//         containerParams.dir = '.'
//     }
//
//     def container = _importFileContainer()
//
//     def execCommand = 'docker exec -t'
//
//     try {
//         def builderPath =  "/root/${containerParams.workspace}/${containerParams.dir}/pipeline/scripts/build_qase_reporter.sh"
//
//         def buildCommand =  [execCommand, containerParams.name, 'sh -c', container._wrapInDoubleQuotes(builderPath)]
//
//         steps.sh(script: buildCommand.join(' ') )
//     } catch (e) {
//         def errorMessage = "Error building publisher in container: ${e.message}"
//
//         container.remove( [ [name: containerParams.name, image: containerParams.image] ] )
//
//         throw new Exception(errorMessage)
//     }
//
//     try {
//         def executablePath =  "/root/${containerParams.workspace}/${containerParams.dir}/reporter"
//
//         def executableCommand =  [execCommand, containerParams.name, 'sh -c', container._wrapInDoubleQuotes(executablePath)]
//
//         steps.sh(script: publishExecutableCommand.join(' ') )
//     } catch (e) {
//         def errorMessage = "Error executing publisher in container: ${e.message}"
//
//         container.remove([ [name: containerParams.name, image: containerParams.image] ])
//
//         throw new Exception(errorMessage)
//     }
// }

// [name: string, image: string, workspace: string, dir: string, resultsXML: string]
def reportFromContainer(Map containerParams) {
    if (! (containerParams.name && containerParams.image))  {
        error 'Container name and image must be provided.'
    }

    if (!containerParams.dir) {
        containerParams.dir = '.'
    }

    def container = _importFileContainer()

    def copyCommand = 'docker cp'

    try {
        def reportPath = "/root/${containerParams.workspace}/${containerParams.dir}/${containerParams.resultsXML}"

        def containerToLocal = "${containerParams.name}:${reportPath} ."

        def copyReportCommand =  [copyCommand, containerToLocal]

        steps.sh(script: copyReportCommand.join(' ') )

        steps.step([$class: 'JUnitResultArchiver', testResults: "**/${containerParams.resultsXML}"])
    } catch (e) {
        def errorMessage = "Error copying results from container: ${e.message}"

        container.remove([ [name: containerParams.name, image: containerParams.image] ])

        throw new Exception(errorMessage)
    }
}
