/**
 * make.groovy
 *
 * GNU Make target execution for Jenkins pipelines.
 *
 * All make commands run inside the rancher-infra-tools Docker container so
 * that no make/tofu/ansible installation is required on the Jenkins agent.
 * The workspace is mounted at /workspace and the working directory inside
 * the container is set to the specified project directory.
 *
 * AWS credentials are optionally forwarded from the calling withCredentials
 * block via -e AWS_ACCESS_KEY_ID and -e AWS_SECRET_ACCESS_KEY.
 *
 * Typical workflow
 * ----------------
 *   def mk = new make()
 *   mk.runTarget(target: 'infra-up', dir: 'qa-infra-automation', makeArgs: 'ENV=airgap')
 *   mk.runTarget(target: 'cluster', dir: 'qa-infra-automation', makeArgs: 'ENV=airgap', passAwsCreds: false)
 *   mk.runTarget(target: 'rancher', dir: 'qa-infra-automation', makeArgs: 'ENV=airgap', passAwsCreds: false)
 */

/**
 * Internal helper — return the Docker image name to use for make commands.
 *
 * Reads the 'infraTools' image from the shared config, which defaults to the
 * value of RANCHER_INFRA_TOOLS_IMAGE or 'rancher-infra-tools:latest'.
 */
def _getImage() {
    def config = new config()
    return config.getDockerImage('infraTools')
}

/**
 * Internal helper — run an arbitrary shell command inside the infra-tools
 * container, mounting the current workspace at /workspace.
 *
 * Parameters:
 *   command      (String,  required) - Shell command to execute inside the container.
 *   workDir      (String,  required) - Working directory inside container (absolute path).
 *   envVars      (Map,     optional) - Additional environment variables to pass.
 *                                      Defaults to empty map.
 *   returnStdout (Boolean, optional) - When true, return the trimmed stdout string
 *                                      instead of the exit status integer.
 *                                      Defaults to false.
 *   mountSsh     (Boolean, optional) - When true, mount .ssh/ at /root/.ssh.
 *                                      Defaults to true.
 *   passAwsCreds (Boolean, optional) - When true, forward AWS_ACCESS_KEY_ID and
 *                                      AWS_SECRET_ACCESS_KEY env vars.
 *                                      Defaults to true.
 *
 * Returns the exit status (int) or trimmed stdout (String) depending on returnStdout.
 */
def _runInContainer(Map config) {
    def workspace = steps.pwd()

    // Build environment variable arguments
    def envArgs = []

    if (config.passAwsCreds != false) {
        envArgs.add('-e AWS_ACCESS_KEY_ID')
        envArgs.add('-e AWS_SECRET_ACCESS_KEY')
    }

    envArgs.add('-e ANSIBLE_HOST_KEY_CHECKING=False')

    if (config.envVars) {
        envArgs.addAll(config.envVars.collect { k, v -> "-e ${k}='${v}'" })
    }

    // Build volume mount arguments
    def volumeArgs = ["-v ${workspace}:/workspace"]

    if (config.mountSsh != false) {
        volumeArgs.add("-v ${workspace}/.ssh:/root/.ssh")
    }

    def cfg = new config()
    def platform = cfg.getDockerPlatform()

    def dockerCommand = "docker run --rm --platform ${platform} ${envArgs.join(' ')} ${volumeArgs.join(' ')} -w ${config.workDir} ${_getImage()} sh -c \"${config.command}\""

    if (config.returnStdout) {
        return steps.sh(script: dockerCommand, returnStdout: true).trim()
    } else {
        return steps.sh(script: dockerCommand, returnStatus: true)
    }
}

/**
 * Run a make target inside the infra-tools container.
 *
 * Constructs `make <target> <makeArgs>` and executes it in the specified
 * working directory inside the container.
 *
 * Parameters:
 *   target       (String,  required) - Make target name (e.g. 'infra-up', 'cluster').
 *   dir          (String,  required) - Project directory relative to workspace
 *                                      (e.g. 'qa-infra-automation'). Used as the
 *                                      working directory inside the container.
 *   makeArgs     (Object,  optional) - Additional make arguments. Can be a String
 *                                      ('ENV=airgap') or List (['ENV=airgap', 'DISTRO=rke2']).
 *                                      Defaults to empty.
 *   envVars      (Map,     optional) - Additional environment variables to pass
 *                                      into the container as -e flags.
 *   returnStdout (Boolean, optional) - When true, return stdout instead of exit status.
 *                                      Defaults to false.
 *   mountSsh     (Boolean, optional) - Mount .ssh into container for Ansible SSH.
 *                                      Defaults to true.
 *   passAwsCreds (Boolean, optional) - Forward AWS credentials into container.
 *                                      Defaults to true.
 *   failOnError  (Boolean, optional) - Fail the build on non-zero exit status.
 *                                      Defaults to true.
 *
 * Returns the exit status (int) or trimmed stdout (String).
 *
 * Examples:
 *   def mk = new make()
 *
 *   // Infrastructure provisioning (needs AWS creds)
 *   mk.runTarget(target: 'infra-up', dir: 'qa-infra-automation', makeArgs: 'ENV=airgap')
 *
 *   // Cluster deployment (no AWS creds needed)
 *   mk.runTarget(target: 'cluster', dir: 'qa-infra-automation', makeArgs: 'ENV=airgap', passAwsCreds: false)
 *
 *   // Get status output as string
 *   def output = mk.runTarget(target: 'status', dir: 'qa-infra-automation', returnStdout: true)
 */
def runTarget(Map config) {
    if (!config.target) {
        steps.error 'Target must be provided for make.runTarget().'
    }
    if (!config.dir) {
        steps.error 'Directory must be provided for make.runTarget().'
    }

    // Build the make command
    def args = ''
    if (config.makeArgs) {
        if (config.makeArgs instanceof List) {
            args = ' ' + config.makeArgs.join(' ')
        } else {
            args = ' ' + config.makeArgs.toString()
        }
    }

    def command = "make ${config.target}${args}"

    steps.echo "Running make ${config.target} in ${config.dir}"

    def status = _runInContainer(
        command:      command,
        workDir:      "/workspace/${config.dir}",
        envVars:      config.envVars ?: [:],
        returnStdout: config.returnStdout ?: false,
        mountSsh:     config.mountSsh,
        passAwsCreds: config.passAwsCreds
    )

    if (config.returnStdout) {
        return status
    }

    if (config.failOnError != false && status != 0) {
        steps.error "make ${config.target} failed with exit status ${status}"
    }

    return status
}
