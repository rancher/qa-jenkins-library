/**
 * generate.groovy
 *
 * Facade for name generation in Jenkins pipelines.
 *
 * Delegates to naming.groovy so that Jenkinsfiles only need to call a single
 * function to get both the container and image names for a build.
 *
 * Usage
 * -----
 *   def names = generate.names()
 *   // names.container → '<jobName><buildNumber>_test'
 *   // names.image     → 'rancher-validation-<jobName><buildNumber>'
 *
 *   def names = generate.names('airgap')
 *   // names.container → '<jobName><buildNumber>_airgap'
 */

/**
 * Generate Docker container and image names for the current build.
 *
 * Delegates to naming.generateNames() with the given suffix so that container
 * and image names are derived consistently from JOB_NAME and BUILD_NUMBER.
 *
 * Parameters:
 *   suffix (String, optional) - Suffix appended to the container name to
 *                               distinguish its purpose. Defaults to 'test'.
 *
 * Returns a Map with keys:
 *   container (String) - Docker container name.
 *   image     (String) - Docker image name.
 *
 * Example:
 *   def names = generate.names('airgap')
 *   container.run(
 *       container: [name: names.container, image: names.image, ...],
 *       test: [...]
 *   )
 */
def names(String suffix = 'test') {
    def naming = new naming()
    return naming.generateNames([suffix: suffix])
}
