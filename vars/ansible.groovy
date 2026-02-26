/**
 * ansible.groovy
 *
 * Ansible playbook execution helpers for Jenkins pipelines.
 *
 * All playbook runs are containerised using the rancher-infra-tools image so
 * that no Ansible installation is required on the Jenkins agent. SSH keys
 * written to .ssh/ in the workspace are mounted read-only into the container
 * at /root/.ssh for host authentication.
 *
 * Usage
 * -----
 *   ansible.runPlaybook(
 *       dir:       'infra/ansible',
 *       inventory: 'inventory/hosts.ini',
 *       playbook:  'site.yml',
 *       extraVars: [cluster_name: env.CLUSTER_NAME, region: env.AWS_REGION],
 *       tags:      'install'
 *   )
 */

/**
 * Internal helper — return the Docker image name for Ansible commands.
 *
 * Currently hard-coded to 'rancher-infra-tools:latest'. Future work may
 * delegate to the shared config module.
 */
def _getImage() {
    return 'rancher-infra-tools:latest'
}

/**
 * Run an Ansible playbook inside the infra-tools container.
 *
 * The workspace is mounted at /workspace and the workspace .ssh directory is
 * mounted read-only at /root/.ssh so that Ansible can reach remote hosts.
 * The working directory inside the container is set to /workspace.
 *
 * Double-quote characters and backslashes in each Ansible argument are escaped
 * before the final command string is assembled to prevent shell injection.
 *
 * Parameters:
 *   dir       (String,  required) - Path to the Ansible project directory
 *                                   (relative to workspace).
 *   inventory (String,  required) - Inventory file path (-i), relative to dir.
 *   playbook  (String,  required) - Playbook file path, relative to dir.
 *   extraVars (Map,     optional) - Key/value pairs passed as --extra-vars.
 *   tags      (String,  optional) - Comma-separated task tags (--tags).
 *   limit     (String,  optional) - Host/group limit pattern (--limit).
 *   verbose   (Boolean, optional) - Enable verbose output (-vvv). Defaults to false.
 *
 * Example:
 *   ansible.runPlaybook(
 *       dir:       'infra/ansible',
 *       inventory: 'inventory/hosts.ini',
 *       playbook:  'site.yml',
 *       extraVars: [cluster_name: env.CLUSTER_NAME],
 *       tags:      'install',
 *       verbose:   true
 *   )
 */
def runPlaybook(Map config) {
    if (!(config.dir && config.inventory && config.playbook)) {
        error 'Directory, inventory, and playbook must be provided.'
    }

    steps.echo "Running Ansible playbook: ${config.playbook}"

    def ansibleArgs = [
        "ansible-playbook",
        "-i ${config.inventory}",
        config.playbook
    ]

    // Add extra variables if provided
    if (config.extraVars) {
        def extraVarsStr = config.extraVars.collect { k, v ->
            "${k}=${v}"
        }.join(' ')
        ansibleArgs.add("--extra-vars \"${extraVarsStr}\"")
    }

    // Add tags if provided
    if (config.tags) {
        ansibleArgs.add("--tags ${config.tags}")
    }

    // Add limit if provided
    if (config.limit) {
        ansibleArgs.add("--limit ${config.limit}")
    }

    // Add verbosity if requested
    if (config.verbose) {
        ansibleArgs.add("-vvv")
    }

    // Properly escape ansible arguments to prevent command injection
    def escapedAnsibleArgs = ansibleArgs.collect { arg ->
        // Escape any double quotes and backslashes in the argument
        arg.replace('\\', '\\\\').replace('"', '\\"')
    }

    def ansibleCommand = "cd ${config.dir} && ${escapedAnsibleArgs.join(' ')}"

    def workspace = steps.pwd()
    def globalConfig = new config()
    def platform = globalConfig.getDockerPlatform()
    def dockerCommand = "docker run --rm --platform ${platform} -v ${workspace}:/workspace -v ${workspace}/.ssh:/root/.ssh:ro -w /workspace ${_getImage()} sh -c \"${ansibleCommand}\""

    def status = steps.sh(script: dockerCommand, returnStatus: true)

    if (status != 0) {
        error "Ansible playbook execution failed with status ${status}"
    }

    steps.echo "Ansible playbook completed successfully"
}

/**
 * Write Ansible variables to a group_vars file on disk.
 *
 * This is a thin wrapper around writeFile that ensures the parent directory
 * exists and logs the operation. The content string should be valid YAML.
 *
 * Parameters:
 *   path    (String, required) - Destination file path (e.g. 'infra/ansible/group_vars/all.yml').
 *   content (String, required) - YAML content to write.
 *
 * Example:
 *   ansible.writeInventoryVars(
 *       path:    'infra/ansible/group_vars/all.yml',
 *       content: "cluster_name: ${env.CLUSTER_NAME}\nregion: ${env.AWS_REGION}\n"
 *   )
 */
def writeInventoryVars(Map config) {
    if (!(config.path && config.content)) {
        error 'Path and content must be provided.'
    }

    steps.echo "Writing Ansible variables to ${config.path}"

    try {
        steps.writeFile file: config.path, text: config.content
        steps.echo "Ansible variables written successfully"
    } catch (e) {
        error "Failed to write Ansible variables: ${e.message}"
    }
}

/**
 * Validate an Ansible inventory file by running `ansible-inventory --list`.
 *
 * Runs directly on the Jenkins agent (not inside a container). Both the
 * directory and inventory path are shell-escaped before use.
 *
 * Parameters:
 *   dir       (String, required) - Directory containing the inventory file.
 *   inventory (String, required) - Inventory file path relative to dir.
 *
 * Returns true when the inventory is valid, false when validation fails
 * (a warning is logged rather than an error so cleanup stages can still run).
 *
 * Example:
 *   boolean valid = ansible.validateInventory(
 *       dir: 'infra/ansible', inventory: 'inventory/hosts.ini'
 *   )
 */
def validateInventory(Map config) {
    if (!(config.dir && config.inventory)) {
        error 'Directory and inventory must be provided.'
    }

    steps.echo "Validating Ansible inventory: ${config.inventory}"

    // Properly escape inventory path to prevent command injection
    def escapedDir = config.dir.replace('\\', '\\\\').replace('"', '\\"')
    def escapedInventory = config.inventory.replace('\\', '\\\\').replace('"', '\\"')

    def validateCommand = "cd ${escapedDir} && ansible-inventory -i ${escapedInventory} --list > /dev/null"

    def status = steps.sh(script: validateCommand, returnStatus: true)

    if (status != 0) {
        steps.echo "Warning: Ansible inventory validation failed"
        return false
    }

    steps.echo "Ansible inventory validated successfully"
    return true
}

/**
 * Run an Ansible playbook inside a pre-existing named Docker container using
 * `docker run --rm` with a custom image and optional environment variables.
 *
 * Use this variant when the infra-tools image is not suitable and a specific
 * container image must be used instead.
 *
 * Parameters:
 *   container (String, required) - Docker image name to run the playbook in.
 *   dir       (String, required) - Host directory to mount at /workspace.
 *   inventory (String, required) - Inventory file path (-i), relative to /workspace.
 *   playbook  (String, required) - Playbook file path, relative to /workspace.
 *   extraVars (Map,    optional) - Key/value pairs passed as --extra-vars.
 *   tags      (String, optional) - Comma-separated task tags (--tags).
 *   envVars   (Map,    optional) - Additional environment variables (-e KEY=VALUE).
 *
 * Example:
 *   ansible.runPlaybookInContainer(
 *       container: 'my-custom-ansible:1.0',
 *       dir:       "${env.WORKSPACE}/infra/ansible",
 *       inventory: 'inventory/hosts.ini',
 *       playbook:  'site.yml'
 *   )
 */
def runPlaybookInContainer(Map config) {
    if (!(config.container && config.dir && config.inventory && config.playbook)) {
        error 'Container, directory, inventory, and playbook must be provided.'
    }

    steps.echo "Running Ansible playbook in container: ${config.container}"

    def ansibleArgs = [
        "ansible-playbook",
        "-i ${config.inventory}",
        config.playbook
    ]

    if (config.extraVars) {
        def extraVarsStr = config.extraVars.collect { k, v ->
            "${k}=${v}"
        }.join(' ')
        ansibleArgs.add("--extra-vars \"${extraVarsStr}\"")
    }

    if (config.tags) {
        ansibleArgs.add("--tags ${config.tags}")
    }

    def envArgs = []
    if (config.envVars) {
        envArgs = config.envVars.collect { k, v -> "-e ${k}=${v}" }
    }

    // Properly escape ansible arguments to prevent command injection
    def escapedAnsibleArgs = ansibleArgs.collect { arg ->
        // Escape any double quotes and backslashes in the argument
        arg.replace('\\', '\\\\').replace('"', '\\"')
    }

    def dockerCommand = [
        "docker run --rm",
        envArgs.join(' '),
        "-v ${config.dir}:/workspace",
        "-w /workspace",
        config.container,
        "-c",
        "\"${escapedAnsibleArgs.join(' ')}\""
    ].join(' ')

    def status = steps.sh(script: dockerCommand, returnStatus: true)

    if (status != 0) {
        error "Ansible playbook in container failed with status ${status}"
    }

    steps.echo "Ansible playbook in container completed successfully"
}
