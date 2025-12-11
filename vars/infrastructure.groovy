// High-level infrastructure helpers for Jenkins pipelines

// Write configuration file with variable substitutions
// [ path: string, content: string, substitutions?: Map ]
def writeConfig(Map config) {
    if (!(config.path && config.content)) {
        error 'Path and content must be provided.'
    }

    steps.echo "Writing configuration to ${config.path}"

    def processedContent = config.content

    // Apply substitutions if provided
    if (config.substitutions) {
        config.substitutions.each { key, value ->
            processedContent = processedContent.replace("\${${key}}", value.toString())
        }
    }

    try {
        // Ensure directory exists
        def dirPath = config.path.substring(0, config.path.lastIndexOf('/'))
        steps.sh "mkdir -p ${dirPath}"

        steps.writeFile file: config.path, text: processedContent
        steps.echo "Configuration written successfully to ${config.path}"
    } catch (e) {
        error "Failed to write configuration: ${e.message}"
    }
}

// Decode and write SSH key from base64, and generate public key
// [ keyContent: string, keyName: string, dir?: string ]
def writeSshKey(Map config) {
    if (!(config.keyContent && config.keyName)) {
        error 'SSH key content and name must be provided.'
    }

    def sshDir = config.dir ?: '.ssh'

    steps.echo "Writing SSH key: ${config.keyName}"

    try {
        // Create SSH directory if it doesn't exist
        steps.sh "mkdir -p ${sshDir}"

        // Decode base64 key content
        def decoded = new String(config.keyContent.decodeBase64())

        // Write private key file
        def keyPath = "${sshDir}/${config.keyName}"
        steps.writeFile file: keyPath, text: decoded

        // Set proper permissions for private key
        steps.sh "chmod 600 ${keyPath}"

        // Generate public key from private key
        // Strip extension (e.g., .pem) and add .pub
        def keyBaseName = config.keyName.replaceAll(/\.[^.]+$/, '')
        def pubKeyPath = "${sshDir}/${keyBaseName}.pub"
        steps.sh "ssh-keygen -y -f ${keyPath} > ${pubKeyPath}"
        steps.sh "chmod 644 ${pubKeyPath}"

        steps.echo "SSH key pair written successfully: ${keyPath} and ${pubKeyPath}"
        return keyPath
    } catch (e) {
        error "Failed to write SSH key: ${e.message}"
    }
}

// Generate unique workspace name
// [ prefix?: string, suffix?: string, includeTimestamp?: bool ]
def generateWorkspaceName(Map config = [:]) {
    def prefix = config.prefix ?: 'jenkins_workspace'
    def buildNumber = env.BUILD_NUMBER ?: 'unknown'

    // Sanitize suffix for Terraform workspace naming (alphanumeric, hyphens, underscores only)
    def sanitizedSuffix = ''
    if (config.suffix) {
        sanitizedSuffix = config.suffix.toString()
            .replaceAll(/[^a-zA-Z0-9-_]/, '-')  // Replace invalid chars with hyphens
            .replaceAll(/-+/, '-')              // Collapse multiple hyphens
            .replaceAll(/^-|-$/, '')            // Remove leading/trailing hyphens
        if (sanitizedSuffix) {
            sanitizedSuffix = "_${sanitizedSuffix}"
        }
    }

    if (config.includeTimestamp != false) {
        def timestamp = new Date().format('yyyyMMddHHmmss')
        return "${prefix}_${buildNumber}${sanitizedSuffix}_${timestamp}"
    }

    return "${prefix}_${buildNumber}${sanitizedSuffix}"
}

// Parse YAML-like content and apply environment variable substitutions
// [ content: string, envVars: Map ]
def parseAndSubstituteVars(Map config) {
    if (!config.content) {
        error 'Content must be provided.'
    }

    def processedContent = config.content

    if (config.envVars) {
        config.envVars.each { key, value ->
            // Replace both ${VAR} and $VAR patterns
            processedContent = processedContent.replaceAll(/\$\{${key}\}/, value.toString())
            processedContent = processedContent.replaceAll(/\$${key}(?![a-zA-Z0-9_])/, value.toString())
        }
    }

    return processedContent
}

// Create directory structure
// [ paths: List<String> ]
def createDirectories(Map config) {
    if (!config.paths) {
        error 'Paths must be provided.'
    }

    config.paths.each { path ->
        steps.echo "Creating directory: ${path}"
        steps.sh "mkdir -p ${path}"
    }
}

// Clean up workspace artifacts
// [ paths: List<String>, force?: bool ]
def cleanupArtifacts(Map config) {
    if (!config.paths) {
        error 'Paths must be provided.'
    }

    steps.echo 'Cleaning up artifacts'

    def forceFlag = config.force ? '-f' : ''

    config.paths.each { path ->
        try {
            steps.sh "rm -rf ${forceFlag} ${path}"
            steps.echo "Removed: ${path}"
        } catch (e) {
            steps.echo "Warning: Could not remove ${path}: ${e.message}"
        }
    }
}

// Archive workspace name for later use
// [ workspaceName: string, fileName?: string ]
def archiveWorkspaceName(Map config) {
    if (!config.workspaceName) {
        error 'Workspace name must be provided.'
    }

    def fileName = config.fileName ?: 'workspace_name.txt'

    steps.echo "Archiving workspace name: ${config.workspaceName}"

    try {
        steps.writeFile file: fileName, text: config.workspaceName
        steps.archiveArtifacts artifacts: fileName, fingerprint: true
        steps.echo "Workspace name archived to ${fileName}"
    } catch (e) {
        error "Failed to archive workspace name: ${e.message}"
    }
}

// Extract archived workspace name
// [ fileName?: string ]
def getArchivedWorkspaceName(Map config = [:]) {
    def fileName = config.fileName ?: 'workspace_name.txt'

    steps.echo "Retrieving archived workspace name from ${fileName}"

    try {
        def workspaceName = steps.readFile(fileName).trim()
        steps.echo "Retrieved workspace name: ${workspaceName}"
        return workspaceName
    } catch (e) {
        error "Failed to retrieve workspace name: ${e.message}"
    }
}
