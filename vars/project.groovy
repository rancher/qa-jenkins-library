// [path: string, branch?: string, repository: string, target?: string]
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
