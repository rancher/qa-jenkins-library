import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName

import static org.assertj.core.api.Assertions.assertThat

/**
 * Tests for vars/s3.groovy — buildUri() pure-logic function.
 *
 * buildUri() constructs S3 URIs from config defaults and caller params.
 * It delegates to config.groovy's getS3Config() which is already tested.
 *
 * Note: s3.groovy uses `new config()` internally. The `new X()` pattern
 * creates a script with an empty binding, so env is not available. We
 * work around this by adding env to the Groovy shell's shared context
 * via the helper's base classloader.
 */
class S3ScriptTest extends BasePipelineTest {

    def script

    @Override
    @BeforeEach
    void setUp() {
        super.setUp()

        // Make env available to scripts created via `new X()` inside loaded scripts.
        // Pre-load config.groovy to register its class, then set env as a metaClass property.
        def configScript = loadScript('config.groovy')
        configScript.class.metaClass.env = binding.getVariable('env')

        script = loadScript('s3.groovy')
    }

    // ── buildUri ──────────────────────────────────────────────────────

    @Test
    @DisplayName('buildUri constructs URI with default bucket and prefix')
    void buildUri_defaults() {
        def uri = script.buildUri(
            workspaceName: 'ws_42',
            s3Key: 'terraform.tfvars'
        )

        assertThat((String) uri).isEqualTo(
            's3://rancher-qa-artifacts/env/ws_42/terraform.tfvars'
        )
    }

    @Test
    @DisplayName('buildUri uses custom bucket when provided')
    void buildUri_customBucket() {
        def uri = script.buildUri(
            workspaceName: 'ws_42',
            s3Key: 'state.tfstate',
            bucket: 'my-custom-bucket'
        )

        assertThat((String) uri).isEqualTo(
            's3://my-custom-bucket/env/ws_42/state.tfstate'
        )
    }

    @Test
    @DisplayName('buildUri handles nested s3Key paths')
    void buildUri_nestedKey() {
        def uri = script.buildUri(
            workspaceName: 'rancher_airgap_100',
            s3Key: 'infra/aws/terraform.tfvars'
        )

        assertThat((String) uri).isEqualTo(
            's3://rancher-qa-artifacts/env/rancher_airgap_100/infra/aws/terraform.tfvars'
        )
    }

    @Test
    @DisplayName('buildUri throws when workspaceName is missing')
    void buildUri_throwsForMissingWorkspaceName() {
        helper.registerAllowedMethod('error', [String.class]) { String msg ->
            throw new RuntimeException(msg)
        }

        RuntimeException ex = null
        try {
            script.buildUri(s3Key: 'file.txt')
        } catch (RuntimeException e) {
            ex = e
        }

        assertThat(ex).isNotNull()
        assertThat((String) ex.message).contains('workspaceName and s3Key must be provided')
    }

    @Test
    @DisplayName('buildUri throws when s3Key is missing')
    void buildUri_throwsForMissingS3Key() {
        helper.registerAllowedMethod('error', [String.class]) { String msg ->
            throw new RuntimeException(msg)
        }

        RuntimeException ex = null
        try {
            script.buildUri(workspaceName: 'ws_42')
        } catch (RuntimeException e) {
            ex = e
        }

        assertThat(ex).isNotNull()
        assertThat((String) ex.message).contains('workspaceName and s3Key must be provided')
    }

    @Test
    @DisplayName('buildUri respects env override for bucket via config')
    void buildUri_envOverrideBucket() {
        def envMap = binding.getVariable('env') as Map
        envMap['S3_ARTIFACT_BUCKET'] = 'overridden-bucket'

        def uri = script.buildUri(
            workspaceName: 'ws_42',
            s3Key: 'file.txt'
        )

        assertThat((String) uri).isEqualTo(
            's3://overridden-bucket/env/ws_42/file.txt'
        )
    }
}
