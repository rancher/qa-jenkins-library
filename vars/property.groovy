/**
 * property.groovy
 *
 * Pipeline environment wrappers for credentials, folder properties, and
 * ANSI colour output.
 *
 * The primary entry point is useWithProperties(), which composes all three
 * wrappers in the correct nesting order so that folder-level parameters and
 * string credentials are both available inside the pipeline body, and build
 * log output is colour-coded.
 *
 * Wrapper nesting order (outermost → innermost):
 *   1. ANSI colour   (useWithColor)
 *   2. Folder props  (useWithFolderProperties)
 *   3. Credentials   (useWithCredentials)
 *   4. caller body
 *
 * Usage
 * -----
 *   property.useWithProperties(['MY_SECRET', 'ANOTHER_CRED']) {
 *       // env.MY_SECRET and env.ANOTHER_CRED are available here
 *       sh 'echo $MY_SECRET'
 *   }
 */

/**
 * Top-level wrapper that sets up ANSI colour output, injects Jenkins folder
 * properties as environment variables, and binds the requested credentials
 * before executing the body closure.
 *
 * Parameters:
 *   credentials (List<String>, required) - Credential IDs to bind. Each ID is
 *                                          also used as the environment variable
 *                                          name (string credentials only).
 *   body        (Closure,      required) - Pipeline steps to execute inside the
 *                                          configured environment.
 *
 * Example:
 *   property.useWithProperties(['AWS_ACCESS_KEY_ID', 'AWS_SECRET_ACCESS_KEY']) {
 *       stage('Deploy') {
 *           tofu.apply(dir: 'infra/aws')
 *       }
 *   }
 */
def useWithProperties(List<String> credentials, Closure body) {
    useWithColor {
        useWithFolderProperties {
            useWithCredentials(credentials, body)
        }
    }
}

/**
 * Wrap the body in an ANSI colour build wrapper using the colour map and
 * foreground/background colours from the UI configuration section.
 *
 * Parameters:
 *   body (Closure, required) - Pipeline steps to execute with colour enabled.
 *
 * Example:
 *   property.useWithColor {
 *       sh 'echo -e "\\033[32mGreen output\\033[0m"'
 *   }
 */
def useWithColor(Closure body) {
    def globalConfig = new config()
    def uiConfig = globalConfig.getUIConfig()
    wrap([$class: 'AnsiColorBuildWrapper', 'colorMapName': uiConfig.colorMapName, 'defaultFg': uiConfig.defaultFg, 'defaultBg': uiConfig.defaultBg]) {
        try {
            body()
        } catch (e) {
            error "[Error]: Caught within usewithColor body: ${e.message}"
        }
    }
}

/**
 * Internal helper — set a list of KEY=VALUE pairs as environment variables
 * for the duration of the body closure.
 *
 * Parameters:
 *   paramsMap (List<String>, optional) - Environment variable pairs in the form
 *                                        "KEY=VALUE". Defaults to empty list.
 *   body      (Closure,      required) - Pipeline steps to execute.
 */
def _useWithEnvironment(paramsMap = [:], Closure body) {
    withEnv(paramsMap) {
        try {
            body()
        } catch (e) {
            error "[Error]: Caught within use withEnv body: ${e.message}"
        }
    }
}

/**
 * Inject all non-empty Jenkins folder properties as environment variables
 * before executing the body closure.
 *
 * Boolean and other non-string property values are coerced to String before
 * being added to the environment. Empty or null values are skipped.
 *
 * Parameters:
 *   body (Closure, required) - Pipeline steps to execute with folder properties
 *                              available as environment variables.
 *
 * Example:
 *   property.useWithFolderProperties {
 *       echo env.MY_FOLDER_PROPERTY
 *   }
 */
def useWithFolderProperties(Closure body) {
    withFolderProperties {
        paramsMap = []
        params.each {
            // Coerce non-string values (e.g., booleans) to String before trim to avoid MissingMethodException
            def v = it.value
            if (v != null) {
                def s = v.toString()
                if (s.trim() != '') {
                    paramsMap << "$it.key=$s"
                }
            }
        }
        try {
            _useWithEnvironment(paramsMap) {
                body()
            }
        } catch (e) {
            error "[Error]: Caught within use withFolderProperties body: ${e.message}"
        }
    }
}

/**
 * Bind a list of Jenkins string credentials as environment variables for the
 * duration of the body closure. Each credential ID is used as both the lookup
 * key in the Jenkins credential store and the environment variable name.
 *
 * Parameters:
 *   credentials (List<String>, required) - Credential IDs to bind.
 *   body        (Closure,      required) - Pipeline steps to execute with
 *                                          credentials bound.
 *
 * Example:
 *   property.useWithCredentials(['GITHUB_TOKEN']) {
 *       sh 'git clone https://$GITHUB_TOKEN@github.com/org/repo.git'
 *   }
 */
def useWithCredentials(List<String> credentials, Closure body) {
    def creds = credentials.collect { cred -> string(credentialsId: cred, variable: cred ) }

    withCredentials(creds) {
        try {
            body()
        } catch (e) {
            error "[Error]: Caught within withCredentials body: ${e.message}"
        }
    }
}
