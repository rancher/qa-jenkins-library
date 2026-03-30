/**
 * airgap.groovy
 *
 * Airgap infrastructure orchestration for Jenkins pipelines.
 *
 * Composes the library's lower-level modules (project, tofu, ansible,
 * infrastructure, naming, s3) into airgap-specific workflow functions.
 * Each function delegates to the existing primitives so that bug fixes
 * in the underlying modules propagate automatically.
 *
 * Typical workflow
 * ----------------
 *   // 1. Check out both repositories
 *   def dirs = airgap.standardCheckout()
 *
 *   // 2. Provision infrastructure (tofu init + apply)
 *   // ... using tofu.initBackend, tofu.createWorkspace, tofu.apply
 *
 *   // 3. Configure Ansible and deploy
 *   airgap.configureAnsible(
 *       ansiblePath: 'qa-infra-automation/ansible/rke2/airgap',
 *       sshKey: [content: env.AWS_SSH_PEM_KEY, name: env.AWS_SSH_PEM_KEY_NAME],
 *       inventoryVars: [content: env.ANSIBLE_VARIABLES, envVars: [...]]
 *   )
 *   airgap.deployRKE2(
 *       dir: 'qa-infra-automation/ansible/rke2/airgap',
 *       inventory: 'inventory/inventory.yml',
 *       playbook: 'playbooks/deploy/rke2-tarball-playbook.yml'
 *   )
 *
 *   // 4. Tear down when done
 *   airgap.teardownInfrastructure(dir: 'infra/aws', name: workspaceName)
 */

/**
 * Check out the standard airgap repositories (tests + qa-infra-automation)
 * with parameterized branches.
 *
 * Delegates to project.checkoutMultiple() with default URLs and branches
 * from the config 'repositories' section. Callers can override any field.
 *
 * Parameters:
 *   testsRepo (Map, optional) - Overrides for the tests repository:
 *     url    (String) - Repo URL (default: config.repositories.tests.url)
 *     branch (String) - Branch    (default: config.repositories.tests.branch)
 *     target (String) - Dir name  (default: config.repositories.tests.target)
 *   infraRepo (Map, optional) - Overrides for qa-infra-automation:
 *     url    (String) - Repo URL (default: config.repositories.qaInfraAutomation.url)
 *     branch (String) - Branch   (default: config.repositories.qaInfraAutomation.branch)
 *     target (String) - Dir name (default: config.repositories.qaInfraAutomation.target)
 *
 * Returns a Map with keys: testsDir, infraDir (relative paths).
 *
 * Example:
 *   def dirs = airgap.standardCheckout()
 *   // dirs.testsDir → './tests'
 *   // dirs.infraDir → './qa-infra-automation'
 *
 *   def dirs = airgap.standardCheckout(testsRepo: [branch: 'release/v2.9'])
 */
def standardCheckout(Map params = [:]) {
    steps.echo 'Checking out airgap repositories'

    def globalConfig = new config()
    def testsDefaults = globalConfig.getRepositoryConfig('tests')
    def infraDefaults = globalConfig.getRepositoryConfig('qaInfraAutomation')

    // Merge caller overrides onto config defaults
    def testsRepo = params.testsRepo ?: [:]
    def infraRepo = params.infraRepo ?: [:]

    def testsEntry = [
        repository: testsRepo.url    ?: testsDefaults.url,
        branch:    testsRepo.branch  ?: testsDefaults.branch,
        target:    testsRepo.target  ?: testsDefaults.target
    ]

    def infraEntry = [
        repository: infraRepo.url    ?: infraDefaults.url,
        branch:    infraRepo.branch  ?: infraDefaults.branch,
        target:    infraRepo.target  ?: infraDefaults.target
    ]

    def dirs = new project().checkoutMultiple([testsEntry, infraEntry])

    return [
        testsDir: dirs[0],
        infraDir: dirs[1]
    ]
}

/**
 * Tear down OpenTofu-managed airgap infrastructure.
 *
 * Delegates to tofu.teardownInfrastructure() which performs:
 * selectWorkspace → destroy → deleteWorkspace.
 *
 * Parameters:
 *   dir     (String, required) - Tofu configuration directory.
 *   name    (String, required) - Workspace name to tear down.
 *   varFile (String, optional) - .tfvars file for destroy.
 *
 * Example:
 *   airgap.teardownInfrastructure(
 *       dir: 'infra/aws',
 *       name: workspaceName,
 *       varFile: 'terraform.tfvars'
 *   )
 */
def teardownInfrastructure(Map config) {
    new tofu().teardownInfrastructure(config)
}

/**
 * Configure Ansible for airgap deployment: write SSH keys, render inventory
 * variables with environment substitution, and optionally validate the inventory.
 *
 * Delegates to infrastructure.writeSshKey(), ansible.writeInventoryVars(),
 * and ansible.validateInventory().
 *
 * Parameters:
 *   sshKey        (Map,   required) - SSH key configuration:
 *     content (String) - Base64-encoded private key content.
 *     name    (String) - Key filename (e.g. 'id_rsa.pem').
 *     dir     (String) - Directory to write key into. Defaults to '.ssh'.
 *   inventoryVars (Map,   required) - Inventory variable configuration:
 *     content (String) - YAML content with ${VAR} placeholders.
 *     path    (String) - Destination path for group_vars file.
 *     envVars (Map)    - Variable substitutions (key → value).
 *   validate      (Boolean, optional) - Validate inventory after writing.
 *                                       Defaults to true.
 *   ansibleDir    (String, optional) - Ansible directory for inventory validation.
 *   inventoryFile (String, optional) - Inventory file path for validation.
 *
 * Returns a Map with keys: keyPath, varsPath.
 *
 * Example:
 *   def paths = airgap.configureAnsible(
 *       sshKey: [content: env.AWS_SSH_PEM_KEY, name: env.AWS_SSH_PEM_KEY_NAME],
 *       inventoryVars: [
 *           content: env.ANSIBLE_VARIABLES,
 *           path: 'qa-infra-automation/ansible/rke2/airgap/inventory/group_vars/all.yml',
 *           envVars: ['RKE2_VERSION': env.RKE2_VERSION]
 *       ]
 *   )
 */
def configureAnsible(Map config) {
    if (!(config.sshKey && config.inventoryVars)) {
        error 'sshKey and inventoryVars must be provided for Ansible configuration.'
    }

    steps.echo 'Configuring Ansible for airgap deployment'

    def infra = new infrastructure()
    def ansible = new ansible()

    // 1. Write SSH key
    def keyPath = infra.writeSshKey(
        keyContent: config.sshKey.content,
        keyName:    config.sshKey.name,
        dir:        config.sshKey.dir ?: '.ssh'
    )

    // 2. Process and write inventory variables
    def processedContent = config.inventoryVars.content
    if (config.inventoryVars.envVars) {
        processedContent = infra.parseAndSubstituteVars(
            content: processedContent,
            envVars: config.inventoryVars.envVars
        )
    }

    // Inject SSH key path if not already present
    def sshKeyPath = "/root/.ssh/${config.sshKey.name}"
    if (!processedContent.contains('ssh_private_key_file:')) {
        processedContent += "\nssh_private_key_file: ${sshKeyPath}"
    }
    if (!processedContent.contains('ansible_ssh_private_key_file:')) {
        processedContent += "\nansible_ssh_private_key_file: ${sshKeyPath}"
    }

    ansible.writeInventoryVars(
        path:    config.inventoryVars.path,
        content: processedContent
    )

    // 3. Optionally validate the inventory
    if (config.validate != false && config.ansibleDir && config.inventoryFile) {
        ansible.validateInventory(
            dir:       config.ansibleDir,
            inventory: config.inventoryFile
        )
    }

    return [
        keyPath:  keyPath,
        varsPath: config.inventoryVars.path
    ]
}

/**
 * Deploy RKE2 via Ansible playbook with configurable retry handling.
 *
 * Wraps ansible.runPlaybook() with a retry loop so that transient SSH
 * connection failures during node bootstrapping do not fail the pipeline.
 *
 * Parameters:
 *   dir       (String, required) - Ansible project directory.
 *   inventory (String, required) - Inventory file path, relative to dir.
 *   playbook  (String, required) - Playbook file path, relative to dir.
 *   extraVars (Map,    optional) - Extra variables for the playbook.
 *   tags      (String, optional) - Comma-separated task tags (--tags).
 *   retries   (int,    optional) - Number of retry attempts. Defaults to 3.
 *   delay     (int,    optional) - Seconds between retries. Defaults to 60.
 *   verbose   (Boolean,optional) - Enable verbose output (-vvv). Defaults to false.
 *
 * Returns true on success. Calls error() if all retries exhausted.
 *
 * Example:
 *   airgap.deployRKE2(
 *       dir:       'qa-infra-automation/ansible/rke2/airgap',
 *       inventory: 'inventory/inventory.yml',
 *       playbook:  'playbooks/deploy/rke2-tarball-playbook.yml',
 *       extraVars: [rke2_version: env.RKE2_VERSION],
 *       retries:   3
 *   )
 */
def deployRKE2(Map config) {
    if (!(config.dir && config.inventory && config.playbook)) {
        error 'dir, inventory, and playbook must be provided for RKE2 deployment.'
    }

    def maxRetries = config.retries ?: 3
    def delaySeconds = config.delay ?: 60

    steps.echo "Deploying RKE2 (retries: ${maxRetries}, delay: ${delaySeconds}s)"

    for (int attempt = 1; attempt <= maxRetries; attempt++) {
        try {
            steps.echo "RKE2 deployment attempt ${attempt}/${maxRetries}"

            new ansible().runPlaybook(
                dir:       config.dir,
                inventory: config.inventory,
                playbook:  config.playbook,
                extraVars: config.extraVars,
                tags:      config.tags,
                verbose:   config.verbose
            )

            steps.echo "RKE2 deployment succeeded on attempt ${attempt}"
            return true
        } catch (e) {
            steps.echo "RKE2 deployment attempt ${attempt} failed: ${e.message}"

            if (attempt < maxRetries) {
                steps.echo "Retrying in ${delaySeconds} seconds..."
                steps.sleep time: delaySeconds, unit: 'SECONDS'
            } else {
                error "RKE2 deployment failed after ${maxRetries} attempts: ${e.message}"
            }
        }
    }
}

/**
 * Deploy Rancher via Ansible helm playbook.
 *
 * This deployment is conditional — when config.enabled is false, the function
 * logs a skip message and returns without running the playbook. This allows
 * callers to gate Rancher deployment on a pipeline parameter.
 *
 * Parameters:
 *   dir       (String,  required) - Ansible project directory.
 *   inventory (String,  required) - Inventory file path, relative to dir.
 *   playbook  (String,  required) - Playbook file path, relative to dir.
 *   extraVars (Map,     optional) - Extra variables for the playbook.
 *   tags      (String,  optional) - Comma-separated task tags (--tags).
 *   enabled   (Boolean, optional) - Whether to run the deployment.
 *                                    Defaults to true.
 *   verbose   (Boolean, optional) - Enable verbose output (-vvv). Defaults to false.
 *
 * Returns true if the playbook was run, false if skipped.
 *
 * Example:
 *   airgap.deployRancher(
 *       dir:       'qa-infra-automation/ansible/rke2/airgap',
 *       inventory: 'inventory/inventory.yml',
 *       playbook:  'playbooks/deploy/rancher-helm-playbook.yml',
 *       enabled:   params.DEPLOY_RANCHER
 *   )
 */
def deployRancher(Map config) {
    if (!(config.dir && config.inventory && config.playbook)) {
        error 'dir, inventory, and playbook must be provided for Rancher deployment.'
    }

    def enabled = config.enabled != false

    if (!enabled) {
        steps.echo 'Rancher deployment skipped (enabled: false)'
        return false
    }

    steps.echo 'Deploying Rancher via Ansible playbook'

    new ansible().runPlaybook(
        dir:       config.dir,
        inventory: config.inventory,
        playbook:  config.playbook,
        extraVars: config.extraVars,
        tags:      config.tags,
        verbose:   config.verbose
    )

    steps.echo 'Rancher deployment completed successfully'
    return true
}
