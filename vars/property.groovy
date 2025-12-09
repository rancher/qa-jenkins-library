// ['MY_CREDS'], body{}
def useWithProperties(List<String> credentials, Closure body) {
    useWithColor {
        useWithFolderProperties {
            useWithCredentials(credentials, body)
        }
    }
}

// body {}
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

// params: map, body {}
def _useWithEnvironment(paramsMap = [:], Closure body) {
    withEnv(paramsMap) {
        try {
            body()
        } catch (e) {
            error "[Error]: Caught within use withEnv body: ${e.message}"
        }
    }
}

// body {}
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

// ['MY_CREDS'], body{}
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
