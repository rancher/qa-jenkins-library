import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName

import static org.assertj.core.api.Assertions.assertThat

/**
 * Tests for vars/naming.groovy — resource naming convention utilities.
 */
class NamingScriptTest extends BasePipelineTest {

    def script

    @Override
    @BeforeEach
    void setUp() {
        super.setUp()
        script = loadScript('naming.groovy')
    }

    // ── extractRancherHost ────────────────────────────────────────────

    @Test
    @DisplayName('extractRancherHost extracts host from cattle-config YAML')
    void extractRancherHost_extractsHost() {
        def config = '''
rancher:
  host: my-rancher.example.com
  adminToken: abc123
'''

        def host = script.extractRancherHost(config: config)

        assertThat(host).isEqualTo('my-rancher.example.com')
    }

    @Test
    @DisplayName('extractRancherHost extracts host regardless of leading whitespace')
    void extractRancherHost_handlesIndentation() {
        def config = '        host:    indented-host.example.com\n'

        def host = script.extractRancherHost(config: config)

        assertThat(host).isEqualTo('indented-host.example.com')
    }

    @Test
    @DisplayName('extractRancherHost returns empty string when host is missing')
    void extractRancherHost_missingHost() {
        def config = '''
rancher:
  adminToken: abc123
'''

        def host = script.extractRancherHost(config: config)

        assertThat(host).isEqualTo('')
    }

    @Test
    @DisplayName('extractRancherHost returns empty string for blank config')
    void extractRancherHost_blankConfig() {
        assertThat(script.extractRancherHost(config: '')).isEqualTo('')
        assertThat(script.extractRancherHost(config: '   ')).isEqualTo('')
    }

    @Test
    @DisplayName('extractRancherHost returns empty string for null config')
    void extractRancherHost_nullConfig() {
        assertThat(script.extractRancherHost(config: null)).isEqualTo('')
    }
}
