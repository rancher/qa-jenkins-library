/**
 * s3.groovy
 *
 * S3 artifact management for Jenkins pipelines.
 *
 * Provides upload, download, and delete operations for S3 objects using the
 * AWS CLI inside the rancher-infra-tools Docker container. AWS credentials
 * are forwarded from the calling withCredentials block via -e flags, so no
 * local AWS installation is required on the Jenkins agent.
 *
 * All S3 paths follow the convention:
 *   s3://<bucket>/<pathPrefix>/<workspaceName>/<s3Key>
 *
 * Typical workflow
 * ----------------
 *   // During setup:
 *   s3.uploadArtifact(
 *       workspaceName: wsName,
 *       localPath: 'infra/aws/terraform.tfvars',
 *       s3Key: 'terraform.tfvars'
 *   )
 *
 *   // During destroy:
 *   s3.downloadArtifact(
 *       workspaceName: wsName,
 *       s3Key: 'terraform.tfvars',
 *       localPath: 'infra/aws/terraform.tfvars'
 *   )
 *
 *   // After destroy:
 *   s3.deleteArtifact(workspaceName: wsName, s3Key: 'terraform.tfvars')
 */

/**
 * Internal helper — return the Docker image name for S3 operations.
 *
 * Uses the same infraTools image as tofu.groovy so that the awscli is
 * available alongside OpenTofu.
 */
def _getImage() {
    def libConfig = new config()
    return libConfig.getDockerImage('infraTools')
}

/**
 * Internal helper — run an arbitrary shell command inside the infra-tools
 * Docker container, mounting the current workspace at /workspace.
 *
 * AWS credentials (AWS_ACCESS_KEY_ID, AWS_SECRET_ACCESS_KEY) are always
 * forwarded via -e flags from the calling withCredentials block. Any
 * additional key/value pairs in envVars are appended as -e KEY='VALUE'.
 *
 * Parameters:
 *   command      (String,  required) - Shell command to execute inside the container.
 *   envVars      (Map,     optional) - Additional environment variables to pass.
 *                                      Defaults to empty map.
 *   returnStdout (Boolean, optional) - When true, return trimmed stdout string
 *                                      instead of exit status integer.
 *                                      Defaults to false.
 *
 * Returns the exit status (int) or trimmed stdout (String) depending on returnStdout.
 */
def _runInContainer(String command, Map envVars = [:], boolean returnStdout = false) {
    def workspace = steps.pwd()

    // Build environment variable arguments
    def envArgs = "-e AWS_ACCESS_KEY_ID -e AWS_SECRET_ACCESS_KEY"
    if (envVars) {
        envArgs += " " + envVars.collect { k, v -> "-e ${k}='${v}'" }.join(' ')
    }

    def libConfig = new config()
    def platform = libConfig.getDockerPlatform()
    def dockerCommand = "docker run --rm --platform ${platform} ${envArgs} -v ${workspace}:/workspace -w /workspace ${_getImage()} sh -c \"${command}\""

    if (returnStdout) {
        return steps.sh(script: dockerCommand, returnStdout: true).trim()
    } else {
        return steps.sh(script: dockerCommand, returnStatus: true)
    }
}

/**
 * Build the full S3 URI for a given workspace and key.
 *
 * Constructs a URI from the configured bucket, path prefix, workspace name,
 * and object key.
 *
 * Parameters:
 *   workspaceName (String, required) - Workspace name for path segmentation.
 *   s3Key         (String, required) - Object key within the workspace path.
 *   bucket        (String, optional) - S3 bucket. Defaults to config s3.bucket.
 *
 * Returns the full s3:// URI string.
 *
 * Example:
 *   def uri = s3.buildUri(workspaceName: 'ws_42', s3Key: 'terraform.tfvars')
 *   // → 's3://rancher-qa-artifacts/env/ws_42/terraform.tfvars'
 */
def buildUri(Map params) {
    if (!(params.workspaceName && params.s3Key)) {
        error 'workspaceName and s3Key must be provided.'
    }

    def libConfig = new config()
    def s3Cfg = libConfig.getS3Config()
    def bucket = params.bucket ?: s3Cfg.bucket
    def prefix = s3Cfg.pathPrefix

    return "s3://${bucket}/${prefix}/${params.workspaceName}/${params.s3Key}"
}

/**
 * Upload a local file to S3.
 *
 * The file is uploaded to the standard path pattern:
 *   s3://<bucket>/<pathPrefix>/<workspaceName>/<s3Key>
 *
 * Parameters:
 *   workspaceName (String, required) - Workspace name for path segmentation.
 *   localPath     (String, required) - Path to the local file (relative to workspace).
 *   s3Key         (String, required) - S3 object key within the workspace path.
 *   bucket        (String, optional) - S3 bucket name. Defaults to config s3.bucket.
 *   region        (String, optional) - AWS region. Defaults to config s3.region.
 *
 * Returns the s3:// URI of the uploaded object.
 *
 * Example:
 *   def uri = s3.uploadArtifact(
 *       workspaceName: wsName,
 *       localPath: 'infra/aws/terraform.tfvars',
 *       s3Key: 'terraform.tfvars'
 *   )
 */
def uploadArtifact(Map params) {
    if (!(params.workspaceName && params.localPath && params.s3Key)) {
        error 'workspaceName, localPath, and s3Key must be provided for upload.'
    }

    def libConfig = new config()
    def s3Cfg = libConfig.getS3Config()
    def bucket = params.bucket ?: s3Cfg.bucket
    def region = params.region ?: s3Cfg.region

    def s3Uri = buildUri(
        workspaceName: params.workspaceName,
        s3Key: params.s3Key,
        bucket: bucket
    )

    steps.echo "Uploading ${params.localPath} to ${s3Uri}"

    def command = "aws s3 cp ${params.localPath} ${s3Uri} --region ${region}"
    def status = _runInContainer(command)

    if (status != 0) {
        error "S3 upload failed with status ${status}"
    }

    steps.echo "Upload complete: ${s3Uri}"
    return s3Uri
}

/**
 * Download a file from S3.
 *
 * The file is downloaded from the standard path pattern:
 *   s3://<bucket>/<pathPrefix>/<workspaceName>/<s3Key>
 *
 * Parameters:
 *   workspaceName (String, required) - Workspace name for path segmentation.
 *   s3Key         (String, required) - S3 object key within the workspace path.
 *   localPath     (String, required) - Destination path (relative to workspace).
 *   bucket        (String, optional) - S3 bucket name. Defaults to config s3.bucket.
 *   region        (String, optional) - AWS region. Defaults to config s3.region.
 *
 * Returns the local file path.
 *
 * Example:
 *   def path = s3.downloadArtifact(
 *       workspaceName: wsName,
 *       s3Key: 'terraform.tfvars',
 *       localPath: 'infra/aws/terraform.tfvars'
 *   )
 */
def downloadArtifact(Map params) {
    if (!(params.workspaceName && params.s3Key && params.localPath)) {
        error 'workspaceName, s3Key, and localPath must be provided for download.'
    }

    def libConfig = new config()
    def s3Cfg = libConfig.getS3Config()
    def bucket = params.bucket ?: s3Cfg.bucket
    def region = params.region ?: s3Cfg.region

    def s3Uri = buildUri(
        workspaceName: params.workspaceName,
        s3Key: params.s3Key,
        bucket: bucket
    )

    steps.echo "Downloading ${s3Uri} to ${params.localPath}"

    def command = "aws s3 cp ${s3Uri} ${params.localPath} --region ${region}"
    def status = _runInContainer(command)

    if (status != 0) {
        error "S3 download failed with status ${status}"
    }

    steps.echo "Download complete: ${params.localPath}"
    return params.localPath
}

/**
 * Delete an object from S3.
 *
 * The object at the standard path pattern is removed:
 *   s3://<bucket>/<pathPrefix>/<workspaceName>/<s3Key>
 *
 * Failures are logged as warnings rather than errors to avoid blocking
 * pipeline cleanup stages.
 *
 * Parameters:
 *   workspaceName (String, required) - Workspace name for path segmentation.
 *   s3Key         (String, required) - S3 object key within the workspace path.
 *   bucket        (String, optional) - S3 bucket name. Defaults to config s3.bucket.
 *   region        (String, optional) - AWS region. Defaults to config s3.region.
 *
 * Example:
 *   s3.deleteArtifact(workspaceName: wsName, s3Key: 'terraform.tfvars')
 */
def deleteArtifact(Map params) {
    if (!(params.workspaceName && params.s3Key)) {
        error 'workspaceName and s3Key must be provided for delete.'
    }

    def libConfig = new config()
    def s3Cfg = libConfig.getS3Config()
    def bucket = params.bucket ?: s3Cfg.bucket
    def region = params.region ?: s3Cfg.region

    def s3Uri = buildUri(
        workspaceName: params.workspaceName,
        s3Key: params.s3Key,
        bucket: bucket
    )

    steps.echo "Deleting ${s3Uri}"

    def command = "aws s3 rm ${s3Uri} --region ${region} || true"
    def status = _runInContainer(command)

    if (status != 0) {
        steps.echo "Warning: S3 delete returned status ${status} (object may not exist)"
    } else {
        steps.echo "S3 object deleted: ${s3Uri}"
    }
}
