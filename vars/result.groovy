/**
 * result.groovy
 *
 * Test result reporting helpers for Jenkins pipelines.
 *
 * Copies JUnit XML result files out of a stopped test container and publishes
 * them to Jenkins using the JUnit plugin so that test trends are tracked in
 * the build history.
 *
 * The container is cleaned up (stopped + removed with its image) if the copy
 * operation fails, preventing stale containers from accumulating on the agent.
 *
 * Usage
 * -----
 *   result.reportFromContainer(
 *       name:       names.container,
 *       image:      names.image,
 *       workspace:  env.WORKSPACE_NAME,
 *       dir:        'validation',
 *       resultsXML: 'results.xml'
 *   )
 */

/**
 * Internal helper — instantiate a container object for cleanup operations.
 *
 * Returns a container instance used to call remove() on error.
 */
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

/**
 * Copy a JUnit XML result file out of a stopped test container and publish it
 * to Jenkins using the JUnit result archiver.
 *
 * The result file is expected at the absolute container path:
 *   /root/<workspace>/<dir>/<resultsXML>
 *
 * If the `docker cp` command fails the container and its image are removed
 * and the error is re-thrown so the build is marked as failed.
 *
 * Parameters:
 *   name       (String, required) - Name of the container to copy results from.
 *   image      (String, required) - Image name used for cleanup on failure.
 *   workspace  (String, required) - Workspace name that forms part of the
 *                                   in-container result path.
 *   dir        (String, optional) - Subdirectory within the workspace. Defaults to '.'.
 *   resultsXML (String, required) - Filename of the JUnit XML result file
 *                                   (e.g. 'results.xml').
 *
 * Example:
 *   result.reportFromContainer(
 *       name:       names.container,
 *       image:      names.image,
 *       workspace:  env.WORKSPACE_NAME,
 *       dir:        'validation',
 *       resultsXML: 'results.xml'
 *   )
 */
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
