// Naming convention utilities for Jenkins shared library

// Generate container and image names based on job context
// [ suffix?: string, prefix?: string, includeBuildNumber?: bool ]
def generateNames(Map params = [:]) {
    def config = new config()
    def namingConfig = config.getNamingConfig()
    
    def suffix = params.suffix ?: namingConfig.containerSuffix
    def prefix = params.prefix ?: namingConfig.imagePrefix
    
    def jobName = env.JOB_NAME ?: 'unknown'
    def buildNumber = env.BUILD_NUMBER ?: '0'
    
    // Extract job name from full path if it contains '/'
    if (jobName.contains('/')) {
        def jobNames = jobName.split('/')
        jobName = jobNames[jobNames.size() - 1]
    }
    
    def containerName = "${jobName}${buildNumber}_${suffix}"
    def imageName = "${prefix}${jobName}${buildNumber}"
    
    return [container: containerName, image: imageName]
}

// Generate workspace name with optional timestamp
// [ prefix?: string, includeTimestamp?: bool ]
def generateWorkspaceName(Map params = [:]) {
    def config = new config()
    def namingConfig = config.getNamingConfig()
    
    def prefix = params.prefix ?: 'jenkins_workspace'
    def includeTimestamp = params.includeTimestamp != false
    def buildNumber = env.BUILD_NUMBER ?: 'unknown'
    
    if (includeTimestamp) {
        def timestamp = new Date().format('yyyyMMddHHmmss')
        return "${prefix}_${buildNumber}_${timestamp}"
    }
    
    return "${prefix}_${buildNumber}"
}

// Generate resource names with count
// [ suffix?: string, prefix?: string, count?: int, maxCount?: int ]
def generateMultipleNames(Map params = [:]) {
    def config = new config()
    def suffix = params.suffix ?: 'test'
    def prefix = params.prefix ?: 'rancher-validation-'
    def count = params.count ?: 1
    def maxCount = params.maxCount ?: 10
    
    // Limit count to maxCount
    if (count > maxCount) {
        count = maxCount
    }
    
    def jobName = env.JOB_NAME ?: 'unknown'
    def buildNumber = env.BUILD_NUMBER ?: '0'
    
    // Extract job name from full path if it contains '/'
    if (jobName.contains('/')) {
        def jobNames = jobName.split('/')
        jobName = jobNames[jobNames.size() - 1]
    }
    
    def resourceNames = []
    
    for (int i = 1; i <= count; i++) {
        def containerName = "${jobName}-${buildNumber}-${suffix}-${i}"
        def imageName = "${prefix}${jobName}-${buildNumber}-${i}"
        
        resourceNames << [containerName: containerName, imageName: imageName]
    }
    
    return resourceNames
}

// Generate SSH key file names
// [ keyType?: string, keyName?: string ]
def generateSshKeyNames(Map params = [:]) {
    def keyType = params.keyType ?: 'pem'
    def keyName = params.keyName ?: 'id_rsa'
    
    def privateKey = "${keyName}.${keyType}"
    def publicKey = "${keyName}.pub"
    
    return [privateKey: privateKey, publicKey: publicKey]
}

// Generate report file names
// [ reportType?: string, suffix?: string ]
def generateReportNames(Map params = [:]) {
    def config = new config()
    def reportType = params.reportType ?: 'results'
    def suffix = params.suffix ?: ''
    
    def xmlFile = "${reportType}${suffix}.xml"
    def jsonFile = "${reportType}${suffix}.json"
    
    return [xml: xmlFile, json: jsonFile]
}

// Generate environment file name
// [ envName?: string, suffix?: string ]
def generateEnvFileName(Map params = [:]) {
    def config = new config()
    def dockerConfig = config.getConfig('docker')
    
    def envName = params.envName ?: 'env'
    def suffix = params.suffix ?: ''
    
    def defaultEnvFile = dockerConfig.defaultEnvFile ?: '.env'
    
    if (suffix) {
        return "${envName}${suffix}.env"
    }
    
    return defaultEnvFile
}

// Sanitize name for use in containers, files, etc.
// [ name: string, replacement?: string ]
def sanitizeName(Map params) {
    if (!params.name) {
        error 'Name must be provided for sanitization'
    }
    
    def name = params.name
    def replacement = params.replacement ?: '_'
    
    // Replace invalid characters with replacement
    def sanitized = name.replaceAll(/[^a-zA-Z0-9._-]/, replacement)
    
    // Remove consecutive replacements
    sanitized = sanitized.replaceAll(/${replacement}+/, replacement)
    
    // Remove leading/trailing replacements
    sanitized = sanitized.replaceAll(/^${replacement}|${replacement}$/, '')
    
    // Ensure name is not empty
    if (!sanitized) {
        sanitized = 'resource'
    }
    
    return sanitized
}

// Validate name meets requirements
// [ name: string, maxLength?: int, minLength?: int ]
def validateName(Map params) {
    if (!params.name) {
        error 'Name must be provided for validation'
    }
    
    def name = params.name
    def maxLength = params.maxLength ?: 255
    def minLength = params.minLength ?: 1
    
    def errors = []
    
    if (name.length() < minLength) {
        errors.add("Name must be at least ${minLength} characters long")
    }
    
    if (name.length() > maxLength) {
        errors.add("Name must be no more than ${maxLength} characters long")
    }
    
    // Check for invalid characters
    if (name =~ /[^a-zA-Z0-9._-]/) {
        errors.add("Name contains invalid characters. Only letters, numbers, dots, hyphens, and underscores are allowed")
    }
    
    if (errors) {
        error "Name validation failed: ${errors.join(', ')}"
    }
    
    return true
}