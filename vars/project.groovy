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

/**
 * Check out multiple Git repositories into separate subdirectories within
 * the Jenkins workspace.
 *
 * Unlike checkout(), this does NOT call deleteDir() before cloning, so it is
 * safe to use when checking out several repositories side-by-side. Each
 * individual checkout uses the CleanBeforeCheckout extension to ensure the
 * target directory is in a clean state.
 *
 * Parameters:
 *   repositories (List<Map>, required) - List of repository configuration maps, each with:
 *     repository (String,  required) - Full HTTPS or SSH URL of the Git repository.
 *     branch     (String,  optional) - Branch name to check out. Defaults to 'main'.
 *     target     (String,  required) - Relative subdirectory within the workspace to
 *                                      clone into.
 *
 * Returns a List of target directory paths (e.g. ['./tests', './infra']).
 *
 * Example:
 *   def dirs = project.checkoutMultiple([
 *       [repository: 'https://github.com/rancher/tests.git',
 *        branch: 'main', target: 'tests'],
 *       [repository: 'https://github.com/rancher/qa-infra-automation.git',
 *        branch: 'main', target: 'infra']
 *   ])
 *   // dirs → ['./tests', './infra']
 */
def checkoutMultiple(List<Map> repositories) {
    if (!repositories) {
        error 'At least one repository must be provided for checkoutMultiple.'
    }

    def targetPaths = []

    repositories.each { repoConfig ->
        def repository = repoConfig.repository
        def branch = repoConfig.branch ?: 'main'
        def target = repoConfig.target

        if (!(repository && target)) {
            error "Each repository entry must include 'repository' (URL) and 'target' (directory). Got: ${repoConfig}"
        }

        steps.echo "Cloning ${repository} (${branch}) into ${target}"

        try {
            steps.checkout([
                $class: 'GitSCM',
                branches: [[name: "*/${branch}"]],
                extensions: scm.extensions + [
                    [$class: 'CleanBeforeCheckout'],
                    [$class: 'RelativeTargetDirectory', relativeTargetDir: target]
                ],
                userRemoteConfigs: [[url: repository]]
            ])
        } catch (e) {
            error "Error checking out [${repository}/${branch}] into ${target}: ${e.message}"
        }

        targetPaths << "./${target}"
    }

    return targetPaths
}
