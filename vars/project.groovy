/**
 * project.groovy
 *
 * Git source checkout helpers for Jenkins pipelines.
 *
 * Wraps the Jenkins GitSCM plugin to perform a clean checkout of a repository
 * branch into a workspace subdirectory.
 *
 * Usage
 * -----
 *   def repoDir = project.checkout(
 *       repository: 'https://github.com/rancher/rancher-tests.git',
 *       branch:     'main',
 *       target:     'validation'
 *   )
 */

/**
 * Check out a Git repository into the Jenkins workspace.
 *
 * The workspace is first wiped (deleteDir) to ensure a clean state, then the
 * requested branch is cloned into the target subdirectory using a CleanCheckout
 * extension. The current workspace path is returned so callers can reference
 * checked-out files with relative paths.
 *
 * Parameters:
 *   repository (String, required) - Full HTTPS or SSH URL of the Git repository.
 *   branch     (String, optional) - Branch name to check out. Defaults to 'main'.
 *   target     (String, optional) - Relative subdirectory within the workspace to
 *                                   clone into. Defaults to '.' (workspace root).
 *
 * Returns the target directory path as a string (e.g. './validation').
 *
 * Example:
 *   def repoDir = project.checkout(
 *       repository: 'https://github.com/rancher/rancher-tests.git',
 *       branch:     env.BRANCH_NAME ?: 'main',
 *       target:     'validation'
 *   )
 *   // Files are now at ./validation/...
 */
def checkout(Map config) {
    def branch = config.branch ?: 'main'
    def repository = config.repository
    def target = config.target

    def workspace = pwd()

    if (!repository) {
        error 'Repository must be provided.'
    }

    if (!target) {
        target = '.'
    }

    steps.deleteDir()

    try {
        steps.checkout([
                $class: 'GitSCM',
                branches: [[name: "*/${branch}"]],
                extensions: scm.extensions + [
                    [$class: 'CleanCheckout'],
                    [$class: 'RelativeTargetDirectory', relativeTargetDir: target ]
                ],
                userRemoteConfigs: [[url: repository]]
            ])
        } catch (e) {
        error "Error checking out [${repository}/${branch}]: ${e.message}"
    }

    return "./${target}"
}
