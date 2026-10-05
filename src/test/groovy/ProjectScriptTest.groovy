import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName

import static org.assertj.core.api.Assertions.assertThat
import static org.assertj.core.api.Assertions.assertThatThrownBy

/**
 * Tests for vars/project.groovy — Git checkout helpers.
 *
 * Tests focus on:
 * - checkout: wipes the workspace once, defaults, parameter validation
 * - checkoutMultiple: never wipes the workspace, so earlier checkouts survive
 * - checkoutMultiple: parameter validation and returned paths
 */
class ProjectScriptTest extends BasePipelineTest {

    def script
    def stepsMock
    def deleteDirCalls
    def checkoutCalls
    def echoLog

    @Override
    @BeforeEach
    void setUp() {
        super.setUp()

        deleteDirCalls = 0
        checkoutCalls = []
        echoLog = []

        // project.groovy reads scm.extensions to keep the job's own extensions
        binding.setVariable('scm', [extensions: []])

        stepsMock = new Object()
        stepsMock.metaClass.deleteDir = { -> deleteDirCalls++ }
        stepsMock.metaClass.checkout = { Map m -> checkoutCalls.add(m) }
        stepsMock.metaClass.echo = { String msg -> echoLog.add(msg) }

        binding.setVariable('steps', stepsMock)

        // error() has to actually fail the build
        helper.registerAllowedMethod('error', [String], { String msg -> throw new RuntimeException(msg) })
        helper.registerAllowedMethod('pwd', [], { -> '/tmp/workspace' })

        script = loadScript('project.groovy')
        script.class.metaClass.steps = stepsMock
    }

    // JenkinsPipelineUnit answers calls named after pipeline steps with its own mock,
    // so reach the script's checkout() directly to test the real implementation.
    def callCheckout(Map config) {
        try {
            return script.getClass().getMethod('checkout', Map).invoke(script, config)
        } catch (java.lang.reflect.InvocationTargetException e) {
            throw e.targetException
        }
    }

    // ── checkout ────────────────────────────────────────────────────────

    @Test
    @DisplayName('checkout wipes the workspace once and returns the target path')
    void checkout_wipesWorkspaceAndReturnsTarget() {
        def dir = callCheckout(repository: 'https://example.com/a.git', branch: 'dev', target: 'validation')

        assertThat(deleteDirCalls).isEqualTo(1)
        assertThat(dir.toString()).isEqualTo('./validation')
        assertThat(checkoutCalls).hasSize(1)
        assertThat(checkoutCalls[0].branches[0].name.toString()).isEqualTo('*/dev')
        assertThat(checkoutCalls[0].userRemoteConfigs[0].url).isEqualTo('https://example.com/a.git')
    }

    @Test
    @DisplayName('checkout defaults to branch main and the workspace root')
    void checkout_defaults() {
        def dir = callCheckout(repository: 'https://example.com/a.git')

        assertThat(dir.toString()).isEqualTo('./.')
        assertThat(checkoutCalls[0].branches[0].name.toString()).isEqualTo('*/main')
    }

    @Test
    @DisplayName('checkout fails when repository is missing')
    void checkout_missingRepository() {
        assertThatThrownBy { callCheckout(branch: 'main', target: 'validation') }
            .hasMessageContaining('Repository must be provided')

        assertThat(deleteDirCalls).isEqualTo(0)
        assertThat(checkoutCalls).isEmpty()
    }

    @Test
    @DisplayName('checkout wraps a failed git checkout in a descriptive error')
    void checkout_wrapsCheckoutFailure() {
        stepsMock.metaClass.checkout = { Map m -> throw new RuntimeException('boom') }

        assertThatThrownBy { callCheckout(repository: 'https://example.com/a.git', branch: 'dev', target: 'validation') }
            .hasMessageContaining('Error checking out [https://example.com/a.git/dev]')
            .hasMessageContaining('boom')
    }

    // ── checkoutMultiple ────────────────────────────────────────────────

    @Test
    @DisplayName('checkoutMultiple never wipes the workspace between repositories')
    void checkoutMultiple_doesNotWipeWorkspace() {
        script.checkoutMultiple([
            [repository: 'https://example.com/a.git', branch: 'main', target: 'tests'],
            [repository: 'https://example.com/b.git', branch: 'dev', target: 'infra']
        ])

        assertThat(deleteDirCalls).isEqualTo(0)
        assertThat(checkoutCalls).hasSize(2)
    }

    @Test
    @DisplayName('checkoutMultiple checks out each repository into its own directory')
    void checkoutMultiple_checksOutEachRepository() {
        script.checkoutMultiple([
            [repository: 'https://example.com/a.git', branch: 'main', target: 'tests'],
            [repository: 'https://example.com/b.git', branch: 'dev', target: 'infra']
        ])

        assertThat(checkoutCalls[0].userRemoteConfigs[0].url).isEqualTo('https://example.com/a.git')
        assertThat(checkoutCalls[0].branches[0].name.toString()).isEqualTo('*/main')
        assertThat(checkoutCalls[0].extensions*.relativeTargetDir).contains('tests')
        assertThat(checkoutCalls[1].userRemoteConfigs[0].url).isEqualTo('https://example.com/b.git')
        assertThat(checkoutCalls[1].branches[0].name.toString()).isEqualTo('*/dev')
        assertThat(checkoutCalls[1].extensions*.relativeTargetDir).contains('infra')
    }

    @Test
    @DisplayName('checkoutMultiple returns the target paths in order')
    void checkoutMultiple_returnsPaths() {
        def dirs = script.checkoutMultiple([
            [repository: 'https://example.com/a.git', target: 'tests'],
            [repository: 'https://example.com/b.git', target: 'infra']
        ])

        assertThat(dirs*.toString()).containsExactly('./tests', './infra')
    }

    @Test
    @DisplayName('checkoutMultiple defaults the branch to main')
    void checkoutMultiple_defaultBranch() {
        script.checkoutMultiple([[repository: 'https://example.com/a.git', target: 'tests']])

        assertThat(checkoutCalls[0].branches[0].name.toString()).isEqualTo('*/main')
    }

    @Test
    @DisplayName('checkoutMultiple fails on an empty list')
    void checkoutMultiple_emptyList() {
        assertThatThrownBy { script.checkoutMultiple([]) }
            .hasMessageContaining('At least one repository must be provided')
    }

    @Test
    @DisplayName('checkoutMultiple fails when an entry has no target')
    void checkoutMultiple_missingTarget() {
        assertThatThrownBy { script.checkoutMultiple([[repository: 'https://example.com/a.git']]) }
            .hasMessageContaining("must include 'repository' (URL) and 'target' (directory)")

        assertThat(checkoutCalls).isEmpty()
    }

    @Test
    @DisplayName('checkoutMultiple fails when an entry has no repository')
    void checkoutMultiple_missingRepository() {
        assertThatThrownBy { script.checkoutMultiple([[target: 'tests']]) }
            .hasMessageContaining("must include 'repository' (URL) and 'target' (directory)")
    }
}
