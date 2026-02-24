/**
 * tofu.groovy
 *
 * OpenTofu (Terraform-compatible) operations for Jenkins pipelines.
 *
 * All commands run inside the rancher-infra-tools Docker container so that
 * no OpenTofu installation is required on the Jenkins agent itself. AWS
 * credentials are forwarded from the calling withCredentials block via
 * -e AWS_ACCESS_KEY_ID and -e AWS_SECRET_ACCESS_KEY.
 *
 * Typical workflow
 * ----------------
 *   tofu.initBackend(dir: 'infra', backendInitScript: 'scripts/init-backend.sh',
 *                    bucket: 'my-bucket', key: 'state/terraform.tfstate', region: 'us-east-1')
 *   tofu.createWorkspace(dir: 'infra', name: workspaceName)
 *   tofu.apply(dir: 'infra', varFile: 'terraform.tfvars')
 *   def outputs = tofu.getOutputs(dir: 'infra')
 *   // ... run tests ...
 *   tofu.destroy(dir: 'infra', varFile: 'terraform.tfvars')
 *   tofu.deleteWorkspace(dir: 'infra', name: workspaceName)
 */

/**
 * Internal helper — return the Docker image name to use for tofu commands.
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
 * AWS credentials (AWS_ACCESS_KEY_ID, AWS_SECRET_ACCESS_KEY) are always
 * forwarded via -e flags; any additional key/value pairs in envVars are
 * appended as -e KEY='VALUE' arguments.
 *
 * Parameters:
 *   command      (String,  required) - Shell command to execute inside the container.
 *   envVars      (Map,     optional) - Additional environment variables to pass.
 *                                      Defaults to empty map.
 *   returnStdout (Boolean, optional) - When true, return the trimmed stdout string
 *                                      instead of the exit status integer.
 *                                      Defaults to false.
 *
 * Returns the exit status (int) or trimmed stdout (String) depending on returnStdout.
 */
def _runInContainer(String command, Map envVars = [:], boolean returnStdout = false) {
    def workspace = steps.pwd()

    // Build environment variable arguments
    // AWS credentials will be automatically inherited from withCredentials block
    def envArgs = "-e AWS_ACCESS_KEY_ID -e AWS_SECRET_ACCESS_KEY"
    if (envVars) {
        envArgs += " " + envVars.collect { k, v -> "-e ${k}='${v}'" }.join(' ')
    }

    def config = new config()
    def platform = config.getDockerPlatform()
    def dockerCommand = "docker run --rm --platform ${platform} ${envArgs} -v ${workspace}:/workspace -w /workspace ${_getImage()} sh -c \"${command}\""

    if (returnStdout) {
        return steps.sh(script: dockerCommand, returnStdout: true).trim()
    } else {
        return steps.sh(script: dockerCommand, returnStatus: true)
    }
}

/**
 * Initialise the OpenTofu S3 backend by invoking the provided backend-init
 * script, which generates backend.tf and runs `tofu init`.
 *
 * Parameters:
 *   dir               (String, required) - Path to the Tofu configuration directory
 *                                          (relative to workspace).
 *   backendInitScript (String, required) - Path to the backend initialisation script
 *                                          (e.g. 'scripts/init-backend.sh').
 *   bucket            (String, required) - S3 bucket name for state storage.
 *   key               (String, required) - S3 object key for the state file.
 *   region            (String, required) - AWS region of the S3 bucket.
 *   dynamodbTable     (String, optional) - DynamoDB table for state locking.
 *
 * Example:
 *   tofu.initBackend(
 *       dir:               'infra/aws',
 *       backendInitScript: 'scripts/init-backend.sh',
 *       bucket:            'rancher-qa-tfstate',
 *       key:               "workspaces/${workspaceName}/terraform.tfstate",
 *       region:            'us-east-1',
 *       dynamodbTable:     'rancher-qa-tflock'
 *   )
 */
def initBackend(Map config) {
    if (!(config.dir && config.backendInitScript && config.bucket && config.key && config.region)) {
        error 'Directory, BackendScript, bucket, key, and region must be provided for backend initialization.'
    }

    steps.echo "Initializing Tofu backend in ${config.dir}"

    // Build the init-backend.sh command
    def scriptArgs = [
        "s3",
        "--bucket ${config.bucket}",
        "--key ${config.key}",
        "--region ${config.region}"
    ]

    if (config.dynamodbTable) {
        scriptArgs.add("--dynamodb-table ${config.dynamodbTable}")
    }

    // Run the init-backend.sh script which generates backend.tf and runs tofu init
    def initCommand = "cd ${config.dir} && ${config.backendInitScript} ${scriptArgs.join(' ')}"

    def envVars = [:]
    // AWS credentials should be passed from the calling context
    // They are available through withCredentials in the Jenkinsfile

    def status = _runInContainer(initCommand, envVars)

    if (status != 0) {
        error "Tofu backend initialization failed with status ${status}"
    }

    steps.echo "Tofu backend initialized successfully"
}

/**
 * Create a new OpenTofu workspace and select it.
 *
 * Parameters:
 *   dir  (String, required) - Tofu configuration directory.
 *   name (String, required) - Workspace name to create.
 *
 * Returns the workspace name.
 *
 * Example:
 *   tofu.createWorkspace(dir: 'infra/aws', name: workspaceName)
 */
def createWorkspace(Map config) {
    if (!(config.dir && config.name)) {
        error 'Directory and workspace name must be provided.'
    }

    steps.echo "Creating and selecting workspace: ${config.name}"

    def command = "tofu -chdir=${config.dir} workspace new ${config.name}"
    def envVars = [:]

    def status = _runInContainer(command, envVars)

    if (status != 0) {
        error "Failed to create workspace ${config.name}"
    }

    steps.echo "Workspace ${config.name} created and selected"
    return config.name
}

/**
 * Select an existing OpenTofu workspace.
 *
 * Parameters:
 *   dir  (String, required) - Tofu configuration directory.
 *   name (String, required) - Workspace name to select.
 *
 * Example:
 *   tofu.selectWorkspace(dir: 'infra/aws', name: workspaceName)
 */
def selectWorkspace(Map config) {
    if (!(config.dir && config.name)) {
        error 'Directory and workspace name must be provided.'
    }

    steps.echo "Selecting workspace: ${config.name}"

    def command = "tofu -chdir=${config.dir} workspace select ${config.name}"
    def envVars = [:]

    def status = _runInContainer(command, envVars)

    if (status != 0) {
        error "Failed to select workspace ${config.name}"
    }

    steps.echo "Workspace ${config.name} selected"
}

/**
 * Run `tofu apply` to create or update infrastructure.
 *
 * Parameters:
 *   dir         (String,  required) - Tofu configuration directory.
 *   varFile     (String,  optional) - Path to a .tfvars file (-var-file).
 *   autoApprove (Boolean, optional) - Skip interactive approval (-auto-approve).
 *                                     Defaults to true.
 *
 * Example:
 *   tofu.apply(dir: 'infra/aws', varFile: 'terraform.tfvars')
 */
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

    def envVars = [:]

    def status = _runInContainer(applyCommand, envVars)

    if (status != 0) {
        error "Tofu apply failed with status ${status}"
    }

    steps.echo "Tofu apply completed successfully"
}

/**
 * Run `tofu destroy` to tear down infrastructure.
 *
 * Parameters:
 *   dir         (String,  required) - Tofu configuration directory.
 *   varFile     (String,  optional) - Path to a .tfvars file (-var-file).
 *   autoApprove (Boolean, optional) - Skip interactive approval (-auto-approve).
 *                                     Defaults to true.
 *
 * Example:
 *   tofu.destroy(dir: 'infra/aws', varFile: 'terraform.tfvars')
 */
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

    def envVars = [:]

    def status = _runInContainer(destroyCommand, envVars)

    if (status != 0) {
        error "Tofu destroy failed with status ${status}"
    }

    steps.echo "Tofu destroy completed successfully"
}

/**
 * Switch back to the default workspace then delete the named workspace.
 *
 * Failures during deletion are logged as warnings rather than errors to
 * avoid blocking pipeline cleanup stages.
 *
 * Parameters:
 *   dir  (String, required) - Tofu configuration directory.
 *   name (String, required) - Workspace name to delete.
 *
 * Example:
 *   tofu.deleteWorkspace(dir: 'infra/aws', name: workspaceName)
 */
def deleteWorkspace(Map config) {
    if (!(config.dir && config.name)) {
        error 'Directory and workspace name must be provided.'
    }

    steps.echo "Deleting workspace: ${config.name}"

    def envVars = [:]

    // First, select default workspace
    _runInContainer("tofu -chdir=${config.dir} workspace select default", envVars)

    // Then delete the target workspace
    def status = _runInContainer("tofu -chdir=${config.dir} workspace delete ${config.name}", envVars)

    if (status != 0) {
        steps.echo "Warning: Failed to delete workspace ${config.name}"
    } else {
        steps.echo "Workspace ${config.name} deleted"
    }
}

/**
 * Retrieve OpenTofu outputs from the current workspace.
 *
 * When a specific output name is given, its raw string value is returned.
 * When no name is given, all outputs are returned as a parsed Map (from
 * `tofu output -json`). If JSON parsing fails the raw string is returned
 * with a warning.
 *
 * Parameters:
 *   dir    (String, required) - Tofu configuration directory.
 *   output (String, optional) - Name of a single output to retrieve.
 *                               When omitted all outputs are returned as a Map.
 *
 * Returns a String (single output) or Map (all outputs).
 *
 * Example:
 *   def ip = tofu.getOutputs(dir: 'infra/aws', output: 'bastion_public_ip')
 *   def all = tofu.getOutputs(dir: 'infra/aws')
 */
def getOutputs(Map config) {
    if (!config.dir) {
        error 'Directory must be provided to get outputs.'
    }

    steps.echo "Retrieving Tofu outputs from ${config.dir}"

    def outputCommand = config.output
        ? "tofu -chdir=${config.dir} output -raw ${config.output}"
        : "tofu -chdir=${config.dir} output -json"

    def envVars = [:]

    def outputJson = _runInContainer(outputCommand, envVars, true)

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
