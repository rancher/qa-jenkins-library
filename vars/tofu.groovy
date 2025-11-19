// Tofu/Terraform operations for Jenkins pipelines

// Initialize Tofu backend with S3 configuration
// [ dir: string, bucket: string, key: string, region: string, dynamodbTable?: string ]
def initBackend(Map config) {
    if (!(config.dir && config.bucket && config.key && config.region)) {
        error 'Directory, bucket, key, and region must be provided for backend initialization.'
    }

    steps.echo "Initializing Tofu backend in ${config.dir}"
    
    def backendConfig = [
        "-backend-config=bucket=${config.bucket}",
        "-backend-config=key=${config.key}",
        "-backend-config=region=${config.region}"
    ]
    
    if (config.dynamodbTable) {
        backendConfig.add("-backend-config=dynamodb_table=${config.dynamodbTable}")
    }
    
    def initCommand = "tofu -chdir=${config.dir} init ${backendConfig.join(' ')}"
    
    def status = steps.sh(script: initCommand, returnStatus: true)
    
    if (status != 0) {
        error "Tofu backend initialization failed with status ${status}"
    }
    
    steps.echo "Tofu backend initialized successfully"
}

// Create and select a new workspace
// [ dir: string, name: string ]
def createWorkspace(Map config) {
    if (!(config.dir && config.name)) {
        error 'Directory and workspace name must be provided.'
    }
    
    steps.echo "Creating and selecting workspace: ${config.name}"
    
    def status = steps.sh(
        script: "tofu -chdir=${config.dir} workspace new ${config.name}",
        returnStatus: true
    )
    
    if (status != 0) {
        error "Failed to create workspace ${config.name}"
    }
    
    steps.echo "Workspace ${config.name} created and selected"
    return config.name
}

// Select an existing workspace
// [ dir: string, name: string ]
def selectWorkspace(Map config) {
    if (!(config.dir && config.name)) {
        error 'Directory and workspace name must be provided.'
    }
    
    steps.echo "Selecting workspace: ${config.name}"
    
    def status = steps.sh(
        script: "tofu -chdir=${config.dir} workspace select ${config.name}",
        returnStatus: true
    )
    
    if (status != 0) {
        error "Failed to select workspace ${config.name}"
    }
    
    steps.echo "Workspace ${config.name} selected"
}

// Run tofu apply
// [ dir: string, varFile?: string, autoApprove?: bool ]
def apply(Map config) {
    if (!config.dir) {
        error 'Directory must be provided for apply operation.'
    }
    
    steps.echo "Running tofu apply in ${config.dir}"
    
    def applyArgs = []
    
    if (config.varFile) {
        applyArgs.add("-var-file=${config.varFile}")
    }
    
    if (config.autoApprove != false) {
        applyArgs.add("-auto-approve")
    }
    
    def applyCommand = "tofu -chdir=${config.dir} apply ${applyArgs.join(' ')}"
    
    def status = steps.sh(script: applyCommand, returnStatus: true)
    
    if (status != 0) {
        error "Tofu apply failed with status ${status}"
    }
    
    steps.echo "Tofu apply completed successfully"
}

// Run tofu destroy
// [ dir: string, varFile?: string, autoApprove?: bool ]
def destroy(Map config) {
    if (!config.dir) {
        error 'Directory must be provided for destroy operation.'
    }
    
    steps.echo "Running tofu destroy in ${config.dir}"
    
    def destroyArgs = []
    
    if (config.varFile) {
        destroyArgs.add("-var-file=${config.varFile}")
    }
    
    if (config.autoApprove != false) {
        destroyArgs.add("-auto-approve")
    }
    
    def destroyCommand = "tofu -chdir=${config.dir} destroy ${destroyArgs.join(' ')}"
    
    def status = steps.sh(script: destroyCommand, returnStatus: true)
    
    if (status != 0) {
        error "Tofu destroy failed with status ${status}"
    }
    
    steps.echo "Tofu destroy completed successfully"
}

// Delete a workspace
// [ dir: string, name: string ]
def deleteWorkspace(Map config) {
    if (!(config.dir && config.name)) {
        error 'Directory and workspace name must be provided.'
    }
    
    steps.echo "Deleting workspace: ${config.name}"
    
    // First, select default workspace
    steps.sh(script: "tofu -chdir=${config.dir} workspace select default", returnStatus: true)
    
    // Then delete the target workspace
    def status = steps.sh(
        script: "tofu -chdir=${config.dir} workspace delete ${config.name}",
        returnStatus: true
    )
    
    if (status != 0) {
        steps.echo "Warning: Failed to delete workspace ${config.name}"
    } else {
        steps.echo "Workspace ${config.name} deleted"
    }
}

// Get tofu outputs as a map
// [ dir: string, output?: string ]
def getOutputs(Map config) {
    if (!config.dir) {
        error 'Directory must be provided to get outputs.'
    }
    
    steps.echo "Retrieving Tofu outputs from ${config.dir}"
    
    def outputCommand = config.output 
        ? "tofu -chdir=${config.dir} output -raw ${config.output}"
        : "tofu -chdir=${config.dir} output -json"
    
    def outputJson = steps.sh(script: outputCommand, returnStdout: true).trim()
    
    if (config.output) {
        return outputJson
    }
    
    try {
        def outputs = readJSON(text: outputJson)
        return outputs
    } catch (e) {
        steps.echo "Warning: Could not parse outputs as JSON"
        return outputJson
    }
}
