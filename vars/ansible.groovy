// Ansible operations for Jenkins pipelines (Docker-based)

// Get the Docker image to use for ansible commands
def _getImage() {
    return 'rancher-infra-tools:latest'
}

// Run an Ansible playbook
// [ dir: string, inventory: string, playbook: string, extraVars?: Map, tags?: string, limit?: string, verbose?: bool ]
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
    
    def ansibleCommand = "cd ${config.dir} && ${ansibleArgs.join(' ')}"
    
    def workspace = steps.pwd()
    def dockerCommand = "docker run --rm -v ${workspace}:/workspace -v ${workspace}/.ssh:/root/.ssh:ro -w /workspace ${_getImage()} sh -c \"${ansibleCommand}\""
    
    def status = steps.sh(script: dockerCommand, returnStatus: true)
    
    if (status != 0) {
        error "Ansible playbook execution failed with status ${status}"
    }
    
    steps.echo "Ansible playbook completed successfully"
}

// Write variables to Ansible inventory group_vars
// [ path: string, content: string ]
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

// Validate Ansible inventory
// [ dir: string, inventory: string ]
def validateInventory(Map config) {
    if (!(config.dir && config.inventory)) {
        error 'Directory and inventory must be provided.'
    }
    
    steps.echo "Validating Ansible inventory: ${config.inventory}"
    
    def validateCommand = "cd ${config.dir} && ansible-inventory -i ${config.inventory} --list > /dev/null"
    
    def status = steps.sh(script: validateCommand, returnStatus: true)
    
    if (status != 0) {
        steps.echo "Warning: Ansible inventory validation failed"
        return false
    }
    
    steps.echo "Ansible inventory validated successfully"
    return true
}

// Run ansible-playbook in a container
// [ container: string, dir: string, inventory: string, playbook: string, extraVars?: Map, tags?: string, envVars?: Map ]
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
    
    def dockerCommand = [
        "docker run --rm",
        envArgs.join(' '),
        "-v ${config.dir}:/workspace",
        "-w /workspace",
        config.container,
        "-c",
        "\"${ansibleArgs.join(' ')}\""
    ].join(' ')
    
    def status = steps.sh(script: dockerCommand, returnStatus: true)
    
    if (status != 0) {
        error "Ansible playbook in container failed with status ${status}"
    }
    
    steps.echo "Ansible playbook in container completed successfully"
}
