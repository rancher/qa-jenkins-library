/**
 * infra.groovy
 *
 * Semantic wrappers around make.runTarget() for the default RKE2/AWS
 * infrastructure lifecycle (DISTRO=rke2 ENV=default PROVIDER=aws).
 *
 * All functions delegate immediately to make.runTarget() — no container
 * logic is implemented here. AWS credentials are forwarded to every
 * target that touches OpenTofu; Ansible-only targets receive
 * passAwsCreds: false.
 *
 * Typical pipeline sequence
 * -------------------------
 *   infra.backendS3(env.INFRA_DIR, params.S3_BUCKET_NAME, params.S3_KEY_PREFIX, params.S3_BUCKET_REGION)
 *   infra.workspaceNew(env.INFRA_DIR, workspaceName)
 *   infra.up(env.INFRA_DIR, workspaceName)
 *   infra.cluster(env.INFRA_DIR, workspaceName)
 *   infra.rancher(env.INFRA_DIR, workspaceName)
 *   // post:
 *   infra.down(env.INFRA_DIR, workspaceName)
 *
 * All make targets default to DISTRO=rke2 ENV=default PROVIDER=aws per
 * the Makefile; no extra args are needed for the standard flow.
 */

/**
 * Configure S3 backend for the tofu module.
 *
 * Runs: make backend-s3 BUCKET=<bucket> KEY=<key> REGION=<region>
 *
 * Parameters:
 *   dir    (String) - Project directory relative to Jenkins workspace
 *                     (e.g. 'qa-infra-automation').
 *   bucket (String) - S3 bucket name for Terraform state.
 *   key    (String) - S3 key prefix for Terraform state.
 *   region (String) - AWS region of the S3 bucket.
 */
def backendS3(String dir, String bucket, String key, String region) {
    make.runTarget(
        target:      'backend-s3',
        dir:         dir,
        makeArgs:    "BUCKET=${bucket} KEY=${key} REGION=${region}",
        passAwsCreds: true
    )
}

/**
 * Create a new tofu workspace.
 *
 * Runs: make workspace-new WORKSPACE=<name>
 *
 * Parameters:
 *   dir           (String) - Project directory relative to Jenkins workspace.
 *   workspaceName (String) - Workspace name (must not be 'default').
 */
def workspaceNew(String dir, String workspaceName) {
    make.runTarget(
        target:      'workspace-new',
        dir:         dir,
        makeArgs:    "WORKSPACE=${workspaceName}",
        passAwsCreds: true
    )
}

/**
 * Provision infrastructure and generate Ansible inventory.
 *
 * Runs: make infra-up WORKSPACE=<name>
 * Defaults to DISTRO=rke2 ENV=default PROVIDER=aws via Makefile defaults.
 *
 * Parameters:
 *   dir           (String) - Project directory relative to Jenkins workspace.
 *   workspaceName (String) - Tofu workspace to provision.
 */
def up(String dir, String workspaceName) {
    make.runTarget(
        target:      'infra-up',
        dir:         dir,
        makeArgs:    "WORKSPACE=${workspaceName}",
        passAwsCreds: true
    )
}

/**
 * Deploy the Kubernetes cluster via Ansible.
 *
 * Runs: make cluster WORKSPACE=<name>
 * Does not forward AWS credentials — cluster deployment is pure Ansible.
 *
 * Parameters:
 *   dir           (String) - Project directory relative to Jenkins workspace.
 *   workspaceName (String) - Tofu workspace (used by Makefile to locate inventory).
 */
def cluster(String dir, String workspaceName) {
    make.runTarget(
        target:       'cluster',
        dir:          dir,
        makeArgs:     "WORKSPACE=${workspaceName}",
        passAwsCreds: false
    )
}

/**
 * Deploy Rancher to the cluster via Ansible.
 *
 * Runs: make rancher WORKSPACE=<name>
 * Does not forward AWS credentials — Rancher deployment is pure Ansible.
 *
 * Parameters:
 *   dir           (String) - Project directory relative to Jenkins workspace.
 *   workspaceName (String) - Tofu workspace (used by Makefile to locate inventory).
 */
def rancher(String dir, String workspaceName) {
    make.runTarget(
        target:       'rancher',
        dir:          dir,
        makeArgs:     "WORKSPACE=${workspaceName}",
        passAwsCreds: false
    )
}

/**
 * Destroy infrastructure for the given workspace (non-interactive).
 *
 * Runs: make infra-down WORKSPACE=<name> AUTO_APPROVE=yes
 * Requires AUTO_APPROVE support in the Makefile (feature/decouple-tofu).
 *
 * Parameters:
 *   dir           (String) - Project directory relative to Jenkins workspace.
 *   workspaceName (String) - Tofu workspace to destroy.
 */
def down(String dir, String workspaceName) {
    make.runTarget(
        target:       'infra-down',
        dir:          dir,
        makeArgs:     "WORKSPACE=${workspaceName} AUTO_APPROVE=yes",
        passAwsCreds: true,
        failOnError:  false
    )
}

/**
 * Get a single output value from the tofu module.
 *
 * Runs: make infra-output WORKSPACE=<name> and returns stdout.
 * Caller is responsible for parsing the output if multiple values are present.
 *
 * Parameters:
 *   dir           (String) - Project directory relative to Jenkins workspace.
 *   workspaceName (String) - Tofu workspace to query.
 *
 * Returns the trimmed stdout string from `tofu output`.
 */
def output(String dir, String workspaceName) {
    return make.runTarget(
        target:       'infra-output',
        dir:          dir,
        makeArgs:     "WORKSPACE=${workspaceName}",
        passAwsCreds: true,
        returnStdout: true
    )
}
