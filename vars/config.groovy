// Centralized configuration management for Jenkins shared library

// Default configuration values
def getDefaultConfig() {
    return [
        // Docker configuration
        docker: [
            images: [
                infraTools: env.RANCHER_INFRA_TOOLS_IMAGE ?: 'rancher-infra-tools:latest'
            ],
            platform: env.DOCKER_PLATFORM ?: 'linux/amd64',
            defaultEnvFile: env.DOCKER_DEFAULT_ENV_FILE ?: '.env'
        ],
        
        // Testing configuration
        testing: [
            defaultTags: env.TEST_DEFAULT_TAGS ?: 'validation',
            defaultTimeout: env.TEST_DEFAULT_TIMEOUT ?: '60m',
            defaultResultsXML: env.TEST_DEFAULT_RESULTS_XML ?: 'results.xml',
            defaultResultsJSON: env.TEST_DEFAULT_RESULTS_JSON ?: 'results.json'
        ],
        
        // Naming conventions
        naming: [
            containerSuffix: env.CONTAINER_SUFFIX ?: 'test',
            imagePrefix: env.IMAGE_PREFIX ?: 'rancher-validation-'
        ],
        
        // UI/Display configuration
        ui: [
            colorMapName: env.COLOR_MAP_NAME ?: 'XTerm',
            defaultFg: env.DEFAULT_FG_COLOR ? env.DEFAULT_FG_COLOR.toInteger() : 2,
            defaultBg: env.DEFAULT_BG_COLOR ? env.DEFAULT_BG_COLOR.toInteger() : 1
        ],
        
        // Path configuration
        paths: [
            defaultDir: env.DEFAULT_DIR ?: '.',
            sshDir: env.SSH_DIR ?: '.ssh',
            validationDir: env.VALIDATION_DIR ?: 'validation'
        ]
    ]
}

// Get specific configuration section
def getConfig(String section) {
    def config = getDefaultConfig()
    return config[section] ?: [:]
}

// Get specific configuration value with fallback
def getConfigValue(String section, String key, def defaultValue = null) {
    def config = getDefaultConfig()
    def sectionConfig = config[section] ?: [:]
    return sectionConfig[key] ?: defaultValue
}

// Merge user configuration with defaults
def mergeConfig(Map userConfig = [:]) {
    def defaultConfig = getDefaultConfig()
    
    // Deep merge configuration
    return deepMerge(defaultConfig, userConfig)
}

// Deep merge two maps recursively
def deepMerge(Map target, Map source) {
    source.each { key, value ->
        if (value instanceof Map && target[key] instanceof Map) {
            target[key] = deepMerge(target[key], value)
        } else {
            target[key] = value
        }
    }
    return target
}

// Validate configuration
def validateConfig(Map config) {
    def errors = []
    
    // Validate required Docker configuration
    if (!config.docker?.images?.infraTools) {
        errors.add("Docker infra tools image is required")
    }
    
    if (!config.docker?.platform) {
        errors.add("Docker platform is required")
    }
    
    // Validate required testing configuration
    if (!config.testing?.defaultTags) {
        errors.add("Default test tags are required")
    }
    
    if (!config.testing?.defaultTimeout) {
        errors.add("Default test timeout is required")
    }
    
    if (errors) {
        error "Configuration validation failed: ${errors.join(', ')}"
    }
    
    return true
}

// Get Docker image name
def getDockerImage(String imageType = 'infraTools') {
    def dockerConfig = getConfig('docker')
    return dockerConfig.images[imageType]
}

// Get Docker platform
def getDockerPlatform() {
    return getConfigValue('docker', 'platform')
}

// Get default test configuration
def getTestConfig() {
    return getConfig('testing')
}

// Get naming configuration
def getNamingConfig() {
    return getConfig('naming')
}

// Get UI configuration
def getUIConfig() {
    return getConfig('ui')
}

// Get path configuration
def getPathConfig() {
    return getConfig('paths')
}