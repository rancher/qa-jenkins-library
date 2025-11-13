package org.rancher.qa.airgap

/**
 * Handles airgap pipeline environment preparation logic that previously lived in the Jenkinsfiles.
 */
class EnvironmentManager implements Serializable {

    private static final long serialVersionUID = 1L

    private final def steps

    EnvironmentManager(def steps) {
        this.steps = steps
    }

    void configureSetupEnvironment(Map ctx) {
        logInfo('Configuring complete environment setup')

        readAndValidateAnsibleVariables(ctx)

        ctx.RKE2_VERSION = ctx.RKE2 ?: PipelineDefaults.DEFAULT_RKE2_VERSION? Wait I replaced? Need accurate.
