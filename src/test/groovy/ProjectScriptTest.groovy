import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

import static org.assertj.core.api.Assertions.assertThat

/**
* Tests for vars/project.groovy — orchestration logic tests.
*
* Project functions are orchestration wrappers that compose lower-level
* modules (tofu, ansible, infrastructure, project, config). Tests here
* focus on testable behaviors:
* - checkoutMultipleIsolated: workspace isolation and cleanup
*/
class ProjectScriptTest extends BasePipelineTest {

    def script
    def checkedOut = []
    def deletedDirectories = []
    def currentDirectory = '.'

    @Override
    @BeforeEach
    void setUp() {
        super.setUp()
        helper.registerAllowedMethod('echo', [String.class]) { }
        helper.registerAllowedMethod('error', [String.class]) { String message ->
            throw new RuntimeException(message)
        }
        helper.registerAllowedMethod('dir', [String.class, Closure.class]) { String path, Closure body ->
            def previous = currentDirectory
            currentDirectory = path
            try {
                body.call()
            } finally {
                currentDirectory = previous
            }
        }
        helper.registerAllowedMethod('deleteDir', []) {
            deletedDirectories << currentDirectory
        }
        helper.registerAllowedMethod('checkout', [Map.class]) { Map scmConfig ->
            checkedOut << [directory: currentDirectory, config: scmConfig]
        }
        script = loadScript('project.groovy')
    }

    @Test
    @DisplayName('isolated checkout scopes cleanup and does not inherit SCM extensions')
    void checkoutMultipleIsolated_keepsRepositoriesSeparate() {
        def paths = script.checkoutMultipleIsolated([
            [repository: 'https://github.com/rancher/tests', branch: 'main', target: 'tests'],
            [repository: 'https://github.com/rancher/qa-infra-automation', branch: 'main', target: 'qa-infra-automation']
        ])

        assertThat(paths).containsExactly('./tests', './qa-infra-automation')
        assertThat(deletedDirectories).containsExactly('tests', 'qa-infra-automation')
        assertThat(checkedOut*.directory).containsExactly('tests', 'qa-infra-automation')
        assertThat(checkedOut[0].config.extensions).containsExactly([$class: 'CleanCheckout'])
        assertThat(checkedOut[1].config.extensions).containsExactly([$class: 'CleanCheckout'])
    }

    @Test
    @DisplayName('isolated checkout rejects workspace root and traversal targets')
    void checkoutMultipleIsolated_rejectsUnsafeTargets() {
        for (target in ['.', '../tests', '/tmp/tests']) {
            RuntimeException failure = null
            try {
                script.checkoutMultipleIsolated([[repository: 'https://example.test/repo', target: target]])
            } catch (RuntimeException e) {
                failure = e
            }
            assertThat(failure).isNotNull()
            assertThat(failure.message).contains('workspace-relative subdirectory')
        }
    }
}
