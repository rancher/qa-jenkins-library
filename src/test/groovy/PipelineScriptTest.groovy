import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName

import static org.assertj.core.api.Assertions.assertThat

/**
 * Tests for vars/pipeline.groovy — resolvePipelineParams() utility function.
 *
 * resolvePipelineParams() parses JOB_NAME and resolves parameters from
 * environment variables with overrides. It depends on config.groovy via
 * `new config()`, so we pre-load config and set env on its metaClass.
 *
 * Other pipeline.groovy functions (standardDockerCleanup, credentialLoader,
 * pipeline templates) require heavy Jenkins step mocking and are better
 * suited for integration tests.
 */
class PipelineScriptTest extends BasePipelineTest {

    def script

    @Override
    @BeforeEach
    void setUp() {
        super.setUp()

        // Make env available to `new config()` inside pipeline.groovy
        def configScript = loadScript('config.groovy')
        configScript.class.metaClass.env = binding.getVariable('env')

        // Mock steps object — pipeline.groovy uses steps.echo
        def stepsMock = new Object()
        stepsMock.metaClass.echo = { String msg -> }
        binding.setVariable('steps', stepsMock)
        // Also set on pipeline script class for `steps.echo` calls
        // (will be applied after loadScript below)

        helper.registerAllowedMethod('echo', [String.class]) { String msg -> }

        script = loadScript('pipeline.groovy')

        // Make steps available as a property on the pipeline script class
        script.class.metaClass.steps = stepsMock
    }

    // ── resolvePipelineParams — JOB_NAME parsing ──────────────────────

    @Test
    @DisplayName('resolvePipelineParams extracts short job name from JOB_NAME')
    void resolvePipelineParams_extractsShortJobName() {
        // JOB_NAME is set to 'test-job' by BasePipelineTest
        def params = script.resolvePipelineParams()

        assertThat((String) params.jobName).isEqualTo('test-job')
    }

    @Test
    @DisplayName('resolvePipelineParams extracts last segment from nested folder path')
    void resolvePipelineParams_nestedJobName() {
        def envMap = binding.getVariable('env') as Map
        envMap['JOB_NAME'] = 'folder/subfolder/my-pipeline'

        def params = script.resolvePipelineParams()

        assertThat((String) params.jobName).isEqualTo('my-pipeline')
    }

    @Test
    @DisplayName('resolvePipelineParams handles JOB_NAME without slashes')
    void resolvePipelineParams_simpleJobName() {
        def envMap = binding.getVariable('env') as Map
        envMap['JOB_NAME'] = 'simple-job'

        def params = script.resolvePipelineParams()

        assertThat((String) params.jobName).isEqualTo('simple-job')
    }

    @Test
    @DisplayName('resolvePipelineParams uses unknown when JOB_NAME is missing')
    void resolvePipelineParams_missingJobName() {
        def envMap = binding.getVariable('env') as Map
        envMap.remove('JOB_NAME')
        binding.setVariable('JOB_NAME', null)

        def params = script.resolvePipelineParams()

        assertThat((String) params.jobName).isEqualTo('unknown')
    }

    // ── resolvePipelineParams — defaults ──────────────────────────────

    @Test
    @DisplayName('resolvePipelineParams returns sensible defaults')
    void resolvePipelineParams_defaults() {
        def params = script.resolvePipelineParams()

        assertThat((String) params.jobName).isEqualTo('test-job')
        assertThat((String) params.branch).isEqualTo('main')
        assertThat((String) params.repo).isEqualTo('https://github.com/rancher/qa-jenkins-library')
        assertThat(params.timeout).isEqualTo(120)
        assertThat((String) params.testsBranch).isEqualTo('main')
        assertThat((String) params.testsRepo).contains('rancher/tests')
        assertThat((String) params.infraBranch).isEqualTo('main')
        assertThat((String) params.infraRepo).contains('rancher/qa-infra-automation')
    }

    // ── resolvePipelineParams — env overrides ─────────────────────────

    @Test
    @DisplayName('resolvePipelineParams reads QA_JENKINS_LIBRARY_BRANCH from env')
    void resolvePipelineParams_envBranch() {
        def envMap = binding.getVariable('env') as Map
        envMap['QA_JENKINS_LIBRARY_BRANCH'] = 'feature/new-stuff'

        def params = script.resolvePipelineParams()

        assertThat((String) params.branch).isEqualTo('feature/new-stuff')
    }

    @Test
    @DisplayName('resolvePipelineParams reads TIMEOUT from env')
    void resolvePipelineParams_envTimeout() {
        def envMap = binding.getVariable('env') as Map
        envMap['TIMEOUT'] = '180'

        def params = script.resolvePipelineParams()

        assertThat(params.timeout).isEqualTo(180)
    }

    @Test
    @DisplayName('resolvePipelineParams reads repo branches from env')
    void resolvePipelineParams_envRepoBranches() {
        def envMap = binding.getVariable('env') as Map
        envMap['RANCHER_TESTS_REPO_BRANCH'] = 'release/v2.9'
        envMap['QA_INFRA_AUTOMATION_REPO_BRANCH'] = 'dev'

        def params = script.resolvePipelineParams()

        assertThat((String) params.testsBranch).isEqualTo('release/v2.9')
        assertThat((String) params.infraBranch).isEqualTo('dev')
    }

    // ── resolvePipelineParams — overrides ─────────────────────────────

    @Test
    @DisplayName('resolvePipelineParams applies caller overrides')
    void resolvePipelineParams_overrides() {
        def params = script.resolvePipelineParams(
            overrides: [
                branch: 'custom-branch',
                timeout: 300,
                testsBranch: 'v2.8',
                infraBranch: 'staging',
                jobName: 'custom-job'
            ]
        )

        assertThat((String) params.jobName).isEqualTo('custom-job')
        assertThat((String) params.branch).isEqualTo('custom-branch')
        assertThat(params.timeout).isEqualTo(300)
        assertThat((String) params.testsBranch).isEqualTo('v2.8')
        assertThat((String) params.infraBranch).isEqualTo('staging')
    }

    @Test
    @DisplayName('resolvePipelineParams overrides take precedence over env')
    void resolvePipelineParams_overridesPrecedence() {
        def envMap = binding.getVariable('env') as Map
        envMap['QA_JENKINS_LIBRARY_BRANCH'] = 'env-branch'
        envMap['TIMEOUT'] = '60'

        def params = script.resolvePipelineParams(
            overrides: [branch: 'override-branch', timeout: 240]
        )

        assertThat((String) params.branch).isEqualTo('override-branch')
        assertThat(params.timeout).isEqualTo(240)
    }

    @Test
    @DisplayName('resolvePipelineParams converts timeout to integer')
    void resolvePipelineParams_timeoutIsInteger() {
        def params = script.resolvePipelineParams()

        assertThat(params.timeout).isInstanceOf(Integer)
    }
}
