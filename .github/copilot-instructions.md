# QA Jenkins Library - Copilot Code Review Instructions

This is a **Jenkins Global Shared Library** written in Groovy. It standardizes CI/CD pipelines across the Rancher QA organization by abstracting infrastructure provisioning, Docker container lifecycle, test execution, Ansible deployment, and OpenTofu/Terraform operations into reusable pipeline functions.

## Architecture

- **`vars/*.groovy`** — All public pipeline functions (Jenkins "global variables")
- **`src/test/groovy/*.groovy`** — Unit tests using JenkinsPipelineUnit + JUnit 5 + AssertJ
- No `src/main` — the library is purely `vars/` scripts
- Functions access Jenkins context through implicit bindings: `env`, `steps`, `pwd()`, `sh()`, `echo()`, `error()`, etc.

## Code Conventions

### Functions use named Map parameters

```groovy
// CORRECT
def runPlaybook(Map config) { ... }
def generateNames(Map params = [:]) { ... }

// WRONG — don't use positional parameters
def runPlaybook(String playbook, String inventory) { ... }
```

### Validate required parameters at function entry

```groovy
def doSomething(Map config) {
    if (!(config.dir && config.name)) {
        error 'Directory and name must be provided.'
    }
    // ...
}
```

### Private helpers use underscore prefix

```groovy
def _getImage() { ... }
def _containerCommand(Map container) { ... }
```

### Cross-module calls use `new`

```groovy
def config = new config()
def infra = new infrastructure()
new tofu().teardownInfrastructure(config)
```

### Docker commands run in containers

Commands are executed via `docker run --rm` with workspace mounted at `/workspace`:

```groovy
def cmd = "docker run --rm --platform ${platform} ${envArgs} -v ${workspace}:/workspace -w /workspace ${image} sh -c \"${command}\""
steps.sh(script: cmd, returnStatus: true)
```

## Documentation Standards

Every public function must have a Javadoc-style comment with:

```groovy
/**
 * Brief description of what the function does.
 *
 * Parameters:
 *   dir (String, required) — Working directory
 *   name (String, optional, default: 'default') — Resource name
 *
 * Returns:
 *   Map with keys [container, image]
 *
 * Example:
 *   def result = container.build(dir: 'build', name: 'my-image')
 */
```

Every `vars/*.groovy` file starts with a module-level comment block containing filename, purpose description, and a usage/workflow example.

## Security

- Validate user-supplied strings before interpolating into shell commands
- Use regex validation for paths: `config.keyName.matches(/[a-zA-Z0-9._-]+/)`
- Escape backslashes and quotes for shell args: `arg.replace('\\', '\\\\').replace('"', '\\"')`
- Never hardcode credentials — use `steps.withCredentials` or `steps.withFolderProperties`

## Test Conventions

- All tests extend `BasePipelineTest` (in `src/test/groovy/`)
- Use JUnit 5 annotations: `@Test`, `@BeforeEach`, `@DisplayName`
- Use AssertJ assertions: `assertThat(actual).isEqualTo(expected)`
- Mock `error()` to throw `RuntimeException` so assertions can catch it
- Mock `steps` via `metaClass` on a plain `Object`
- Organize tests by function with section headers

## Review Priorities

When reviewing code in this repo, prioritize:

1. **Shell injection** — ensure user input is validated/escaped before interpolation into shell commands
2. **Parameter validation** — every function must validate required parameters
3. **Documentation** — all public functions need Javadoc with Parameters/Returns/Example
4. **Error handling** — use `error()` for fatal issues, `steps.echo` for warnings
5. **Naming conventions** — underscore prefix for private helpers, Map params for public functions
6. **Test coverage** — new functions need tests in `src/test/groovy/`
