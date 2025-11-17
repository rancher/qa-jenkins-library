package org.rancher.qa.airgap

/**
 * Handles artifact extraction and archiving tasks for the airgap pipelines.
 */
class ArtifactManager implements Serializable {

    private static final long serialVersionUID = 1L

    private final def steps

    ArtifactManager(def steps) {
        this.steps = steps
    }

    void extractFromVolume(String volumeName, String destination = 'artifacts') {
        if (!volumeName) {
            steps.error('VALIDATION_VOLUME is required for artifact extraction')
        }

        steps.echo "${PipelineDefaults.LOG_PREFIX_INFO} ${timestamp()} Extracting artifacts from ${volumeName}"
        steps.sh "mkdir -p ${destination}"

        def script = """
            docker run --rm \
                -v ${volumeName}:/source \
                -v ${steps.pwd()}/${destination}:/dest \
                alpine:latest \
                sh -c '
                    set -e
                    # Create destination if it does not exist
                    mkdir -p /dest

                    # Copy artifacts directory recursively
                    if [ -d "/source/artifacts" ]; then
                        echo "Copying /source/artifacts/* to /dest/"
                        cp -r /source/artifacts/* /dest/ 2>/dev/null || echo "No files in artifacts directory"
                    else
                        echo "No artifacts directory found in /source"
                    fi

                    # Copy individual files from root of shared volume
                    for file in kubeconfig.yaml deployment-summary.json ansible-inventory.yml infrastructure-outputs.json; do
                        if [ -f "/source/\$file" ]; then
                            echo "Copying /source/\$file to /dest/"
                            cp "/source/\$file" /dest/
                        fi
                    done

                    # List what was extracted for debugging
                    echo "Files extracted to artifacts:"
                    ls -lah /dest/ || true
                '
        """.stripIndent()
        steps.sh script
    }

    void archiveArtifacts(List patterns) {
        if (!patterns) {
            return
        }
        def joined = patterns.join(',')
        steps.archiveArtifacts artifacts: joined, allowEmptyArchive: true
        steps.echo "${PipelineDefaults.LOG_PREFIX_INFO} ${timestamp()} Archived artifacts: ${joined}"
    }

    private static String timestamp() {
        new Date().format('yyyy-MM-dd HH:mm:ss')
    }

}
