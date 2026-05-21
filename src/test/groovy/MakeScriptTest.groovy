import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName

import static org.assertj.core.api.Assertions.assertThat

/**
 * Tests for vars/make.groovy — make target execution in Docker containers.
 *
 * Tests focus on:
 * - runTarget: parameter validation (target required, dir required)
 * - runTarget: make command construction (target, makeArgs string, makeArgs list)
 * - runTarget: Docker command flags (workDir, exit status, returnStdout)
 */
class MakeScriptTest extends BasePipelineTest {

    def script
    def stepsMock
    def echoLog = []
    def capturedShCommand = null
    def capturedShReturnStdout = false

    @Override
    @BeforeEach
    void setUp() {
        super.setUp()

        // Make env available to `new config()` inside make.groovy
        def configScript = loadScript('config.groovy')
        configScript.class.metaClass.env = binding.getVariable('env')

        // Capture echo output for assertions
        echoLog = []

        // Mock sh to capture Docker commands
        capturedShCommand = null
        capturedShReturnStdout = false

        // Build steps mock with ALL methods that make.groovy calls via steps.*
        stepsMock = new Object()
        stepsMock.metaClass.pwd = { -> '/tmp/workspace' }
        stepsMock.metaClass.echo = { String msg -> echoLog.add(msg) }
        stepsMock.metaClass.error = { String msg -> throw new RuntimeException(msg) }
        stepsMock.metaClass.sh = { Map m ->
            capturedShCommand = m['script'] as String
            capturedShReturnStdout = m['returnStdout'] as Boolean ?: false
            if (capturedShReturnStdout) {
                return '  node1 Ready  \n'
            }
            return 0
        }

        binding.setVariable('steps', stepsMock)

        script = loadScript('make.groovy')
        script.class.metaClass.steps = stepsMock
    }

    // ── runTarget — parameter validation ────────────────────────────────

    @Test
    @DisplayName('runTarget requires target parameter')
    void runTarget_requiresTarget() {
        RuntimeException ex = null
        try {
            script.runTarget(dir: 'qa-infra-automation')
        } catch (RuntimeException e) {
            ex = e
        }

        assertThat(ex).isNotNull()
        assertThat((String) ex.message).contains('Target must be provided')
    }

    @Test
    @DisplayName('runTarget requires dir parameter')
    void runTarget_requiresDir() {
        RuntimeException ex = null
        try {
            script.runTarget(target: 'infra-up')
        } catch (RuntimeException e) {
            ex = e
        }

        assertThat(ex).isNotNull()
        assertThat((String) ex.message).contains('Directory must be provided')
    }

    @Test
    @DisplayName('runTarget requires both target and dir')
    void runTarget_requiresBothParams() {
        RuntimeException ex = null
        try {
            script.runTarget([:])
        } catch (RuntimeException e) {
            ex = e
        }

        assertThat(ex).isNotNull()
        assertThat((String) ex.message).contains('Target must be provided')
    }

    // ── runTarget — make command construction ────────────────────────────

    @Test
    @DisplayName('runTarget constructs make command with target only')
    void runTarget_targetOnly() {
        script.runTarget(target: 'status', dir: 'qa-infra-automation', failOnError: false)

        assertThat(capturedShCommand).isNotNull()
        assertThat(capturedShCommand).contains('make status')
    }

    @Test
    @DisplayName('runTarget constructs make command with string makeArgs')
    void runTarget_stringMakeArgs() {
        script.runTarget(target: 'cluster', dir: 'qa-infra-automation', makeArgs: 'ENV=airgap', failOnError: false)

        assertThat(capturedShCommand).isNotNull()
        assertThat(capturedShCommand).contains('make cluster ENV=airgap')
    }

    @Test
    @DisplayName('runTarget constructs make command with list makeArgs')
    void runTarget_listMakeArgs() {
        script.runTarget(
            target: 'infra-up',
            dir: 'qa-infra-automation',
            makeArgs: ['ENV=airgap', 'DISTRO=rke2'],
            failOnError: false
        )

        assertThat(capturedShCommand).isNotNull()
        assertThat(capturedShCommand).contains('make infra-up ENV=airgap DISTRO=rke2')
    }

    // ── runTarget — Docker working directory ─────────────────────────────

    @Test
    @DisplayName('runTarget sets working directory to /workspace/<dir>')
    void runTarget_workingDirectory() {
        script.runTarget(target: 'cluster', dir: 'my-project', failOnError: false)

        assertThat(capturedShCommand).isNotNull()
        assertThat(capturedShCommand).contains('-w /workspace/my-project')
    }

    // ── runTarget — exit status handling ─────────────────────────────────

    @Test
    @DisplayName('runTarget fails build on non-zero exit when failOnError is true')
    void runTarget_failsOnError() {
        // Override stepsMock.sh to return non-zero
        stepsMock.metaClass.sh = { Map m -> return 1 }

        RuntimeException ex = null
        try {
            script.runTarget(target: 'cluster', dir: 'qa-infra-automation')
        } catch (RuntimeException e) {
            ex = e
        }

        assertThat(ex).isNotNull()
        assertThat((String) ex.message).contains('make cluster failed with exit status 1')
    }

    @Test
    @DisplayName('runTarget returns exit status when failOnError is false')
    void runTarget_returnsStatusOnError() {
        // Override stepsMock.sh to return 2
        stepsMock.metaClass.sh = { Map m -> return 2 }

        def status = script.runTarget(target: 'status', dir: 'qa-infra-automation', failOnError: false)

        assertThat(status).isEqualTo(2)
    }

    @Test
    @DisplayName('runTarget returns 0 on success')
    void runTarget_returnsZeroOnSuccess() {
        def status = script.runTarget(target: 'cluster', dir: 'qa-infra-automation', failOnError: false)
        assertThat(status).isEqualTo(0)
    }

    @Test
    @DisplayName('runTarget uses returnStdout when configured')
    void runTarget_returnStdout() {
        def output = script.runTarget(target: 'status', dir: 'qa-infra-automation', returnStdout: true)
        assertThat(output).isEqualTo('node1 Ready')
    }

    // ── runTarget — echo logging ─────────────────────────────────────────

    @Test
    @DisplayName('runTarget logs the target and directory being executed')
    void runTarget_logsExecution() {
        script.runTarget(target: 'rancher', dir: 'qa-infra-automation', failOnError: false)
        assertThat(echoLog).contains('Running make rancher in qa-infra-automation')
    }
}
