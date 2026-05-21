import com.lesfurets.jenkins.unit.BasePipelineTest
import org.junit.jupiter.api.BeforeEach

/**
 * Base test class for all Jenkins shared library tests.
 *
 * Extends JenkinsPipelineUnit's BasePipelineTest to provide the
 * mocked Jenkins runtime (steps, env, etc.) and configures the
 * library source path to point at the vars/ directory.
 */
class BasePipelineTest extends BasePipelineTest {

    @BeforeEach
    void setUp() {
        scriptRoots = ['vars']
        scriptExtension = 'groovy'
        super.setUp()

        // Provide default env vars so most tests don't need to set them.
        // In JenkinsPipelineUnit, env is a separate mock map accessed via
        // script.env.X in the loaded scripts.
        def envMap = binding.getVariable('env') as Map
        envMap['BUILD_NUMBER'] = '42'
        envMap['JOB_NAME'] = 'test-job'
        envMap['WORKSPACE'] = '/tmp/workspace'

        // Also set as binding variables for direct binding.X access
        binding.setVariable('BUILD_NUMBER', '42')
        binding.setVariable('JOB_NAME', 'test-job')
        binding.setVariable('WORKSPACE', '/tmp/workspace')
    }
}
