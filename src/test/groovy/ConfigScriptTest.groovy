import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName

import static org.assertj.core.api.Assertions.assertThat

/**
 * Tests for vars/config.groovy — centralized configuration management.
 *
 * The config script is mostly pure data/logic with minimal Jenkins step
 * dependencies, making it an ideal candidate for thorough unit testing.
 * Only validateConfig() calls error() which needs a mock.
 */
class ConfigScriptTest extends BasePipelineTest {

    def script

    @Override
    @BeforeEach
    void setUp() {
        super.setUp()
        script = loadScript('config.groovy')
    }

    // ── getDefaultConfig ──────────────────────────────────────────────

    @Test
    @DisplayName('getDefaultConfig returns all expected top-level sections')
    void getDefaultConfig_returnsAllSections() {
        def cfg = script.getDefaultConfig()

        assertThat(cfg).containsKeys(
            'docker', 'testing', 'naming', 'ui', 'paths',
            'repositories', 's3', 'pipeline'
        )
    }

    @Test
    @DisplayName('getDefaultConfig docker section has required keys')
    void getDefaultConfig_dockerSection() {
        def docker = script.getDefaultConfig().docker

        assertThat(docker.platform).isEqualTo('linux/amd64')
        assertThat(docker.defaultEnvFile).isEqualTo('.env')
        assertThat(docker.images.infraTools).isEqualTo('rancher-infra-tools:latest')
    }

    @Test
    @DisplayName('getDefaultConfig testing section has required keys')
    void getDefaultConfig_testingSection() {
        def testing = script.getDefaultConfig().testing

        assertThat(testing.defaultTags).isEqualTo('validation')
        assertThat(testing.defaultTimeout).isEqualTo('60m')
        assertThat(testing.defaultResultsXML).isEqualTo('results.xml')
        assertThat(testing.defaultResultsJSON).isEqualTo('results.json')
    }

    @Test
    @DisplayName('getDefaultConfig naming section has required keys')
    void getDefaultConfig_namingSection() {
        def naming = script.getDefaultConfig().naming

        assertThat(naming.containerSuffix).isEqualTo('test')
        assertThat(naming.imagePrefix).isEqualTo('rancher-validation-')
    }

    @Test
    @DisplayName('getDefaultConfig paths section has required keys')
    void getDefaultConfig_pathsSection() {
        def paths = script.getDefaultConfig().paths

        assertThat(paths.defaultDir).isEqualTo('.')
        assertThat(paths.sshDir).isEqualTo('.ssh')
        assertThat(paths.validationDir).isEqualTo('validation')
    }

    @Test
    @DisplayName('getDefaultConfig repositories section has tests and qaInfraAutomation')
    void getDefaultConfig_repositoriesSection() {
        def repos = script.getDefaultConfig().repositories

        assertThat(repos.tests.url).contains('rancher/tests.git')
        assertThat(repos.tests.branch).isEqualTo('main')
        assertThat(repos.tests.target).isEqualTo('tests')

        assertThat(repos.qaInfraAutomation.url).contains('rancher/qa-infra-automation.git')
        assertThat(repos.qaInfraAutomation.branch).isEqualTo('main')
    }

    @Test
    @DisplayName('getDefaultConfig s3 section has required keys')
    void getDefaultConfig_s3Section() {
        def s3 = script.getDefaultConfig().s3

        assertThat(s3.bucket).isEqualTo('rancher-qa-artifacts')
        assertThat(s3.region).isEqualTo('us-east-1')
        assertThat(s3.profile).isEqualTo('default')
        assertThat(s3.pathPrefix).isEqualTo('env')
    }

    @Test
    @DisplayName('getDefaultConfig pipeline section has defaultTimeout')
    void getDefaultConfig_pipelineSection() {
        def pipeline = script.getDefaultConfig().pipeline

        assertThat(pipeline.defaultTimeout).isEqualTo('120')
    }

    @Test
    @DisplayName('getDefaultConfig reads environment variable overrides')
    void getDefaultConfig_envOverrides() {
        // JenkinsPipelineUnit exposes env as a LinkedHashMap
        def envMap = binding.getVariable('env') as Map
        envMap['DOCKER_PLATFORM'] = 'linux/arm64'
        envMap['TEST_DEFAULT_TAGS'] = 'smoke'
        envMap['S3_ARTIFACT_BUCKET'] = 'custom-bucket'

        def cfg = script.getDefaultConfig()

        assertThat(cfg.docker.platform).isEqualTo('linux/arm64')
        assertThat(cfg.testing.defaultTags).isEqualTo('smoke')
        assertThat(cfg.s3.bucket).isEqualTo('custom-bucket')
    }

    // ── getConfig ─────────────────────────────────────────────────────

    @Test
    @DisplayName('getConfig returns the requested section')
    void getConfig_returnsSection() {
        def docker = script.getConfig('docker')

        assertThat(docker).isNotNull()
        assertThat(docker.platform).isEqualTo('linux/amd64')
    }

    @Test
    @DisplayName('getConfig returns empty map for unknown section')
    void getConfig_unknownSectionReturnsEmpty() {
        def result = script.getConfig('nonexistent')

        assertThat(result).isEmpty()
    }

    // ── getConfigValue ────────────────────────────────────────────────

    @Test
    @DisplayName('getConfigValue returns a specific value from a section')
    void getConfigValue_returnsValue() {
        def platform = script.getConfigValue('docker', 'platform')

        assertThat(platform).isEqualTo('linux/amd64')
    }

    @Test
    @DisplayName('getConfigValue returns default when key is missing')
    void getConfigValue_returnsDefaultForMissingKey() {
        def result = script.getConfigValue('docker', 'nonexistent', 'fallback')

        assertThat(result).isEqualTo('fallback')
    }

    @Test
    @DisplayName('getConfigValue returns null when no default provided and key missing')
    void getConfigValue_returnsNullWithoutDefault() {
        def result = script.getConfigValue('docker', 'nonexistent')

        assertThat(result).isNull()
    }

    @Test
    @DisplayName('getConfigValue returns default for unknown section')
    void getConfigValue_unknownSectionReturnsDefault() {
        def result = script.getConfigValue('unknown', 'key', 'safe-default')

        assertThat(result).isEqualTo('safe-default')
    }

    // ── deepMerge ─────────────────────────────────────────────────────

    @Test
    @DisplayName('deepMerge merges nested maps recursively')
    void deepMerge_mergesNestedMaps() {
        def target = [a: [b: 1, c: 2], d: 3]
        def source = [a: [b: 10]]

        def result = script.deepMerge(target, source)

        assertThat(result.a.b).isEqualTo(10)
        assertThat(result.a.c).isEqualTo(2)
        assertThat(result.d).isEqualTo(3)
    }

    @Test
    @DisplayName('deepMerge overwrites scalars')
    void deepMerge_overwritesScalars() {
        def target = [x: 'old']
        def source = [x: 'new']

        def result = script.deepMerge(target, source)

        assertThat(result.x).isEqualTo('new')
    }

    @Test
    @DisplayName('deepMerge adds new keys from source')
    void deepMerge_addsNewKeys() {
        def target = [a: 1]
        def source = [b: 2]

        def result = script.deepMerge(target, source)

        assertThat(result).containsEntry('a', 1)
        assertThat(result).containsEntry('b', 2)
    }

    @Test
    @DisplayName('deepMerge with empty source returns target unchanged')
    void deepMerge_emptySource() {
        def target = [a: 1, b: [c: 2]]

        def result = script.deepMerge(target, [:])

        assertThat(result.a).isEqualTo(1)
        assertThat(result.b.c).isEqualTo(2)
    }

    @Test
    @DisplayName('deepMerge replaces map with scalar when types differ')
    void deepMerge_replacesMapWithScalar() {
        def target = [a: [b: 1]]
        def source = [a: 'replaced']

        def result = script.deepMerge(target, source)

        assertThat(result.a).isEqualTo('replaced')
    }

    // ── mergeConfig ───────────────────────────────────────────────────

    @Test
    @DisplayName('mergeConfig with no overrides returns defaults')
    void mergeConfig_noOverrides() {
        def cfg = script.mergeConfig()

        assertThat(cfg.docker.platform).isEqualTo('linux/amd64')
        assertThat(cfg.testing.defaultTags).isEqualTo('validation')
    }

    @Test
    @DisplayName('mergeConfig applies user overrides on top of defaults')
    void mergeConfig_appliesOverrides() {
        def cfg = script.mergeConfig([docker: [platform: 'linux/arm64']])

        assertThat(cfg.docker.platform).isEqualTo('linux/arm64')
        // Other docker defaults preserved
        assertThat(cfg.docker.defaultEnvFile).isEqualTo('.env')
        // Other sections preserved
        assertThat(cfg.testing.defaultTags).isEqualTo('validation')
    }

    @Test
    @DisplayName('mergeConfig deep-merges nested structures')
    void mergeConfig_deepMerges() {
        def cfg = script.mergeConfig([
            docker: [images: [infraTools: 'custom-image:v2']]
        ])

        assertThat(cfg.docker.images.infraTools).isEqualTo('custom-image:v2')
        assertThat(cfg.docker.platform).isEqualTo('linux/amd64')
        assertThat(cfg.docker.defaultEnvFile).isEqualTo('.env')
    }

    // ── Convenience accessors ─────────────────────────────────────────

    @Test
    @DisplayName('getDockerImage returns infra tools image by default')
    void getDockerImage_defaultType() {
        def image = script.getDockerImage()

        assertThat(image).isEqualTo('rancher-infra-tools:latest')
    }

    @Test
    @DisplayName('getDockerPlatform returns default platform')
    void getDockerPlatform_default() {
        def platform = script.getDockerPlatform()

        assertThat(platform).isEqualTo('linux/amd64')
    }

    @Test
    @DisplayName('getTestConfig returns full testing section')
    void getTestConfig_returnsSection() {
        def testing = script.getTestConfig()

        assertThat(testing.defaultTags).isEqualTo('validation')
        assertThat(testing.defaultTimeout).isEqualTo('60m')
        assertThat(testing.defaultResultsXML).isEqualTo('results.xml')
        assertThat(testing.defaultResultsJSON).isEqualTo('results.json')
    }

    @Test
    @DisplayName('getNamingConfig returns full naming section')
    void getNamingConfig_returnsSection() {
        def naming = script.getNamingConfig()

        assertThat(naming.containerSuffix).isEqualTo('test')
        assertThat(naming.imagePrefix).isEqualTo('rancher-validation-')
    }

    @Test
    @DisplayName('getUIConfig returns full ui section')
    void getUIConfig_returnsSection() {
        def ui = script.getUIConfig()

        assertThat(ui.colorMapName).isEqualTo('XTerm')
        assertThat(ui.defaultFg).isEqualTo(2)
        assertThat(ui.defaultBg).isEqualTo(1)
    }

    @Test
    @DisplayName('getPathConfig returns full paths section')
    void getPathConfig_returnsSection() {
        def paths = script.getPathConfig()

        assertThat(paths.defaultDir).isEqualTo('.')
        assertThat(paths.sshDir).isEqualTo('.ssh')
        assertThat(paths.validationDir).isEqualTo('validation')
    }

    @Test
    @DisplayName('getRepositoryConfig returns named repository config')
    void getRepositoryConfig_returnsRepo() {
        def repo = script.getRepositoryConfig('tests')

        assertThat(repo.url).contains('rancher/tests.git')
        assertThat(repo.branch).isEqualTo('main')
        assertThat(repo.target).isEqualTo('tests')
    }

    @Test
    @DisplayName('getRepositoryConfig returns empty map for unknown repo')
    void getRepositoryConfig_unknownRepo() {
        def repo = script.getRepositoryConfig('nonexistent')

        assertThat(repo).isEmpty()
    }

    @Test
    @DisplayName('getS3Config returns full s3 section')
    void getS3Config_returnsSection() {
        def s3 = script.getS3Config()

        assertThat(s3.bucket).isEqualTo('rancher-qa-artifacts')
        assertThat(s3.region).isEqualTo('us-east-1')
        assertThat(s3.profile).isEqualTo('default')
        assertThat(s3.pathPrefix).isEqualTo('env')
    }

    @Test
    @DisplayName('getPipelineConfig returns full pipeline section')
    void getPipelineConfig_returnsSection() {
        def pipeline = script.getPipelineConfig()

        assertThat(pipeline.defaultTimeout).isEqualTo('120')
    }

    // ── validateConfig ────────────────────────────────────────────────

    @Test
    @DisplayName('validateConfig passes with valid defaults')
    void validateConfig_passesWithDefaults() {
        def cfg = script.getDefaultConfig()

        // Mock the error() step so it throws instead of failing the build
        helper.registerAllowedMethod('error', [String.class]) { String msg ->
            throw new RuntimeException(msg)
        }

        def result = script.validateConfig(cfg)

        assertThat(result).isTrue()
    }

    @Test
    @DisplayName('validateConfig throws when docker image is missing')
    void validateConfig_throwsForMissingDockerImage() {
        helper.registerAllowedMethod('error', [String.class]) { String msg ->
            throw new RuntimeException(msg)
        }

        def cfg = [docker: [platform: 'linux/amd64'], testing: [defaultTags: 't', defaultTimeout: '5m']]

        RuntimeException ex = null
        try {
            script.validateConfig(cfg)
        } catch (RuntimeException e) {
            ex = e
        }

        assertThat(ex).isNotNull()
        assertThat(ex.message).contains('Docker infra tools image is required')
    }

    @Test
    @DisplayName('validateConfig throws when docker platform is missing')
    void validateConfig_throwsForMissingPlatform() {
        helper.registerAllowedMethod('error', [String.class]) { String msg ->
            throw new RuntimeException(msg)
        }

        def cfg = [docker: [images: [infraTools: 'img']], testing: [defaultTags: 't', defaultTimeout: '5m']]

        RuntimeException ex = null
        try {
            script.validateConfig(cfg)
        } catch (RuntimeException e) {
            ex = e
        }

        assertThat(ex).isNotNull()
        assertThat(ex.message).contains('Docker platform is required')
    }

    @Test
    @DisplayName('validateConfig accumulates all errors before throwing')
    void validateConfig_accumulatesErrors() {
        helper.registerAllowedMethod('error', [String.class]) { String msg ->
            throw new RuntimeException(msg)
        }

        // Missing docker image, platform, AND test tags
        def cfg = [docker: [:], testing: [defaultTimeout: '5m']]

        RuntimeException ex = null
        try {
            script.validateConfig(cfg)
        } catch (RuntimeException e) {
            ex = e
        }

        assertThat(ex).isNotNull()
        assertThat(ex.message).contains('Docker infra tools image is required')
        assertThat(ex.message).contains('Docker platform is required')
        assertThat(ex.message).contains('Default test tags are required')
    }
}
