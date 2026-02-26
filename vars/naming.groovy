/**
 * naming.groovy
 *
 * Resource naming convention utilities for Jenkins pipelines.
 *
 * Generates consistent names for Docker containers, images, workspaces,
 * SSH key files, report files, and environment files. All names are derived
 * from the Jenkins JOB_NAME and BUILD_NUMBER so they are unique per build
 * and traceable back to the originating job.
 *
 * Usage
 * -----
 *   def names    = naming.generateNames()
 *   def wsName   = naming.generateWorkspaceName(prefix: 'rancher_airgap')
 *   def keyNames = naming.generateSshKeyNames(keyType: 'pem', keyName: 'aws_key')
 */

/**
 * Generate Docker container and image names for the current build.
 *
 * The container name takes the form:  <jobName><buildNumber>_<suffix>
 * The image name takes the form:      <prefix><jobName><buildNumber>
 *
 * When JOB_NAME contains path separators (folder/job) only the final segment
 * is used.
 *
 * Parameters:
 *   suffix (String, optional) - Container name suffix. Defaults to the value of
 *                               the 'naming.containerSuffix' config key ('test').
 *   prefix (String, optional) - Image name prefix. Defaults to the value of
 *                               the 'naming.imagePrefix' config key ('rancher-validation-').
 *
 * Returns a Map with keys:
 *   container (String) - Generated container name.
 *   image     (String) - Generated image name.
 *
 * Example:
 *   def names = naming.generateNames(suffix: 'airgap')
 *   // names.container → 'my-job42_airgap'
 *   // names.image     → 'rancher-validation-my-job42'
 */
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

/**
 * Generate a unique workspace name for use as a Terraform workspace identifier.
 *
 * The name takes the form: <prefix>_<buildNumber>[_<timestamp>]
 *
 * Parameters:
 *   prefix           (String,  optional) - Name prefix. Defaults to 'jenkins_workspace'.
 *   includeTimestamp (Boolean, optional) - Append a yyyyMMddHHmmss timestamp.
 *                                          Defaults to true.
 *
 * Returns the generated workspace name string.
 *
 * Example:
 *   def wsName = naming.generateWorkspaceName(prefix: 'rancher_airgap')
 *   // → 'rancher_airgap_42_20260223141500'
 */
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

/**
 * Generate names for multiple container/image pairs, indexed with a counter.
 *
 * Useful when a single build needs to spin up several parallel containers.
 * The count is capped at maxCount to prevent runaway resource allocation.
 *
 * Container name form: <jobName>-<buildNumber>-<suffix>-<index>
 * Image name form:     <prefix><jobName>-<buildNumber>-<index>
 *
 * Parameters:
 *   count    (int,    optional) - Number of name pairs to generate. Defaults to 1.
 *   maxCount (int,    optional) - Upper bound on count. Defaults to 10.
 *   suffix   (String, optional) - Suffix appended to container names. Defaults to 'test'.
 *   prefix   (String, optional) - Prefix prepended to image names. Defaults to 'rancher-validation-'.
 *
 * Returns a List of Maps, each with keys:
 *   containerName (String)
 *   imageName     (String)
 *
 * Example:
 *   def nameList = naming.generateMultipleNames(count: 3, suffix: 'airgap')
 */
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

/**
 * Generate SSH private and public key filenames.
 *
 * Parameters:
 *   keyType (String, optional) - File extension for the private key (e.g. 'pem', 'rsa').
 *                                Defaults to 'pem'.
 *   keyName (String, optional) - Base name of the key files. Defaults to 'id_rsa'.
 *
 * Returns a Map with keys:
 *   privateKey (String) - Private key filename (e.g. 'id_rsa.pem').
 *   publicKey  (String) - Public key filename  (e.g. 'id_rsa.pub').
 *
 * Example:
 *   def keys = naming.generateSshKeyNames(keyType: 'pem', keyName: 'aws_key')
 *   // keys.privateKey → 'aws_key.pem'
 *   // keys.publicKey  → 'aws_key.pub'
 */
def generateSshKeyNames(Map params = [:]) {
    def keyType = params.keyType ?: 'pem'
    def keyName = params.keyName ?: 'id_rsa'

    def privateKey = "${keyName}.${keyType}"
    def publicKey = "${keyName}.pub"

    return [privateKey: privateKey, publicKey: publicKey]
}

/**
 * Generate test result report filenames.
 *
 * Parameters:
 *   reportType (String, optional) - Base name for the report files. Defaults to 'results'.
 *   suffix     (String, optional) - Optional suffix appended before the extension.
 *                                   Defaults to '' (no suffix).
 *
 * Returns a Map with keys:
 *   xml  (String) - JUnit XML report filename.
 *   json (String) - JSON report filename.
 *
 * Example:
 *   def reports = naming.generateReportNames(reportType: 'test-results', suffix: '_airgap')
 *   // reports.xml  → 'test-results_airgap.xml'
 *   // reports.json → 'test-results_airgap.json'
 */
def generateReportNames(Map params = [:]) {
    def config = new config()
    def reportType = params.reportType ?: 'results'
    def suffix = params.suffix ?: ''

    def xmlFile = "${reportType}${suffix}.xml"
    def jsonFile = "${reportType}${suffix}.json"

    return [xml: xmlFile, json: jsonFile]
}

/**
 * Generate the Docker env-file filename.
 *
 * When a suffix is provided the name is constructed as <envName><suffix>.env.
 * When no suffix is given the default env file from the Docker configuration
 * section is returned (typically '.env').
 *
 * Parameters:
 *   envName (String, optional) - Base env-file name. Defaults to 'env'.
 *   suffix  (String, optional) - Suffix appended to the name. Defaults to '' (no suffix).
 *
 * Returns the env-file filename string.
 *
 * Example:
 *   def envFile = naming.generateEnvFileName(suffix: '_airgap')
 *   // → 'env_airgap.env'
 */
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

/**
 * Sanitize an arbitrary string for use as a container name, filename, or
 * other identifier where only alphanumeric characters, dots, hyphens, and
 * underscores are permitted.
 *
 * Invalid characters are replaced with the replacement string (default '_').
 * Consecutive replacements are collapsed and leading/trailing replacements
 * are stripped. If the result is empty, 'resource' is returned.
 *
 * Parameters:
 *   name        (String, required) - The raw name to sanitize.
 *   replacement (String, optional) - Character(s) to substitute for invalid
 *                                    characters. Defaults to '_'.
 *
 * Returns the sanitized name string.
 *
 * Example:
 *   def safe = naming.sanitizeName(name: 'my job/name#1', replacement: '-')
 *   // → 'my-job-name-1'
 */
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

/**
 * Validate that a name string meets length and character requirements.
 *
 * Calls error() if any constraint is violated, so pipeline execution stops
 * with a descriptive message.
 *
 * Parameters:
 *   name      (String, required) - Name to validate.
 *   maxLength (int,    optional) - Maximum allowed length. Defaults to 255.
 *   minLength (int,    optional) - Minimum required length. Defaults to 1.
 *
 * Returns true when the name is valid.
 *
 * Example:
 *   naming.validateName(name: containerName, maxLength: 63)
 */
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
