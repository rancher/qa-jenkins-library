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
    wrap([$class: 'AnsiColorBuildWrapper', 'colorMapName': 'XTerm', 'defaultFg': 2, 'defaultBg':1]) {
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
            if (it.value && it.value.trim() != '') {
                paramsMap << "$it.key=$it.value"
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
