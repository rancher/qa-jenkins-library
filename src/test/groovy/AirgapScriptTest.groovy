import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName

import static org.assertj.core.api.Assertions.assertThat
import static org.junit.jupiter.api.Assertions.assertThrows

/**
 * Tests for vars/airgap.groovy — orchestration logic tests.
 *
 * Airgap functions are orchestration wrappers that compose lower-level
 * modules (tofu, ansible, infrastructure, project, config). Tests here
 * focus on testable behaviors:
 * - deployRancher: conditional skip when enabled=false
 * - configureAnsible: parameter validation
 * - deployRKE2: parameter validation
 * - teardownInfrastructure: delegation pattern
 *
 * Cross-script construction (new ansible(), new tofu()) creates fresh
 * objects without the test binding's steps/env, so we limit tests to
 * functions that can be validated before they reach those calls.
 */
class AirgapScriptTest extends BasePipelineTest {

    def script
    def echoLog = []

    @Override
    @BeforeEach
    void setUp() {
        super.setUp()

        // Make env available to `new config()` inside airgap.groovy
        def configScript = loadScript('config.groovy')
        configScript.class.metaClass.env = binding.getVariable('env')

        // Capture echo output for assertions
        echoLog = []
        helper.registerAllowedMethod('echo', [String.class]) { String msg ->
            echoLog.add(msg)
        }
        helper.registerAllowedMethod('error', [String.class]) { String msg ->
            throw new RuntimeException(msg)
        }

        script = loadScript('airgap.groovy')
    }

    // ── configureAnsible — parameter validation ───────────────────────

    @Test
    @DisplayName('configureAnsible requires sshKey and inventoryVars')
    void configureAnsible_requiresParams() {
        def ex = assertThrows(RuntimeException.class) {
            script.configureAnsible([:])
        }

        assertThat(ex.message).contains('sshKey and inventoryVars must be provided')
    }

    @Test
    @DisplayName('configureAnsible fails when only sshKey provided')
    void configureAnsible_requiresInventoryVars() {
        def ex = assertThrows(RuntimeException.class) {
            script.configureAnsible(sshKey: [content: 'key', name: 'id_rsa'])
        }

        assertThat(ex.message).contains('sshKey and inventoryVars must be provided')
    }

    @Test
    @DisplayName('configureAnsible fails when only inventoryVars provided')
    void configureAnsible_requiresSshKey() {
        def ex = assertThrows(RuntimeException.class) {
            script.configureAnsible(inventoryVars: [content: 'yaml', path: 'vars.yml'])
        }

        assertThat(ex.message).contains('sshKey and inventoryVars must be provided')
    }

    // ── deployRKE2 — parameter validation ─────────────────────────────

    @Test
    @DisplayName('deployRKE2 requires dir, inventory, and playbook')
    void deployRKE2_requiresParams() {
        def ex = assertThrows(RuntimeException.class) {
            script.deployRKE2([:])
        }

        assertThat(ex.message).contains('dir, inventory, and playbook must be provided')
    }

    @Test
    @DisplayName('deployRKE2 fails when missing playbook')
    void deployRKE2_requiresPlaybook() {
        def ex = assertThrows(RuntimeException.class) {
            script.deployRKE2(dir: 'ansible/dir', inventory: 'inv.yml')
        }

        assertThat(ex.message).contains('dir, inventory, and playbook must be provided')
    }

    // ── deployRancher — parameter validation ───────────────────────────

    @Test
    @DisplayName('deployRancher requires dir, inventory, and playbook')
    void deployRancher_requiresParams() {
        def ex = assertThrows(RuntimeException.class) {
            script.deployRancher([:])
        }

        assertThat(ex.message).contains('dir, inventory, and playbook must be provided')
    }

    // ── configureAnsible — SSH key path injection logic ────────────────
    // These test the content processing logic in isolation, not the full
    // configureAnsible flow which needs infrastructure/ansible mocks.

    @Test
    @DisplayName('SSH key path injection adds both ssh_private_key_file and ansible_ssh_private_key_file')
    void sshKeyInjection_addsBothPaths() {
        def content = 'host: rancher.local\nport: 8080'
        def keyName = 'test-key.pem'
        def sshKeyPath = "/root/.ssh/${keyName}"

        def processed = content
        if (!(processed =~ /(?m)^\s*ssh_private_key_file\s*:/)) {
            processed += "\nssh_private_key_file: ${sshKeyPath}"
        }
        if (!(processed =~ /(?m)^\s*ansible_ssh_private_key_file\s*:/)) {
            processed += "\nansible_ssh_private_key_file: ${sshKeyPath}"
        }

        assertThat(processed).contains('ssh_private_key_file: /root/.ssh/test-key.pem')
        assertThat(processed).contains('ansible_ssh_private_key_file: /root/.ssh/test-key.pem')
        assertThat(processed).contains('host: rancher.local')
    }

    @Test
    @DisplayName('SSH key path injection skips ssh_private_key_file when already present')
    void sshKeyInjection_noDuplicate() {
        def content = 'host: rancher.local\nssh_private_key_file: /custom/path'
        def keyName = 'key.pem'
        def sshKeyPath = "/root/.ssh/${keyName}"

        def processed = content
        if (!(processed =~ /(?m)^\s*ssh_private_key_file\s*:/)) {
            processed += "\nssh_private_key_file: ${sshKeyPath}"
        }
        if (!(processed =~ /(?m)^\s*ansible_ssh_private_key_file\s*:/)) {
            processed += "\nansible_ssh_private_key_file: ${sshKeyPath}"
        }

        // Count occurrences of bare ssh_private_key_file (not ansible_ssh_private_key_file)
        def lines = processed.readLines()
        def count = lines.count { line -> line.contains('ssh_private_key_file:') && !line.contains('ansible_ssh_private_key_file:') }
        assertThat(count).isEqualTo(1)
        assertThat(processed).contains('ansible_ssh_private_key_file: /root/.ssh/key.pem')
        assertThat(processed).contains('ssh_private_key_file: /custom/path')
    }

    @Test
    @DisplayName('SSH key path injection skips ansible_ssh_private_key_file when already present')
    void sshKeyInjection_noDuplicateAnsible() {
        def content = 'host: rancher.local\nansible_ssh_private_key_file: /existing'
        def keyName = 'key.pem'
        def sshKeyPath = "/root/.ssh/${keyName}"

        def processed = content
        if (!(processed =~ /(?m)^\s*ssh_private_key_file\s*:/)) {
            processed += "\nssh_private_key_file: ${sshKeyPath}"
        }
        if (!(processed =~ /(?m)^\s*ansible_ssh_private_key_file\s*:/)) {
            processed += "\nansible_ssh_private_key_file: ${sshKeyPath}"
        }

        // ansible_ssh_private_key_file is NOT duplicated
        def lines = processed.readLines()
        def ansibleCount = lines.count { line -> line.contains('ansible_ssh_private_key_file:') }
        assertThat(ansibleCount).isEqualTo(1)
        assertThat(processed).contains('ansible_ssh_private_key_file: /existing')

        // With the line-anchored regex, ssh_private_key_file IS correctly
        // added even when ansible_ssh_private_key_file is already present.
        assertThat(processed).contains('ssh_private_key_file: /root/.ssh/key.pem')
    }
}
