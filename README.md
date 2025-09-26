# QA Jenkins Shared Library
A centralized Jenkins Shared Library for standardizing and simplifying CI/CD pipelines for Rancher QA projects.

## Overview
This repository contains a collection of shared pipeline steps and functions designed to reduce boilerplate code in Jenkinsfiles across the Rancher QA organization. By centralizing common logic, we can ensure our pipelines are consistent, easy to create, and simpler to maintain.

The core principle is to abstract complex scripted logic into simple, reusable functions.

**How It Works**
> Once a Jenkinsfile imports the `qa-jenkins-library`, it can use all the shared functions, regardless of which repository the Jenkinsfile is in.

This is made possible by configuring this repository as a Global Pipeline Library within our Jenkins instance.

## Goals
- **Simplify Jenkinsfile Creation:** Abstract common tasks into single-line function calls.

- **Standardize Pipelines:** Ensure all QA projects follow similar, well-defined stages for testing and reporting.

- **Reduce Duplication:** Write code once and use it everywhere.

- **Improve Maintainability:** Update a function in this central library, and all pipelines that use it will get the update automatically.

## Usage Example
To use the functions in this library, you must first import it at the top of your Jenkinsfile. This example shows how simple a pipeline can become by leveraging the shared functions.

```groovy
@Library('qa-jenkins-library') _

node {
    library 'qa-jenkins-library'

    def path = 'go/src/github.com/rancher/tests'

    def workspace

    def testContainer

    //useWithProperties function under vars directory from property.groovy
    property.useWithProperties(['MY_CRED', 'MY_OTHER_CRED']) {
        stage('Checkout') {
            workspace = project.checkout(target: path, branch: env.BRANCH, repository: env.REPO)
        }

        dir(workspace) {
                stage('Prepare Credentials') {
                    //prepare function under vars directory, from container.groovy
                    container.prepare(workspace: workspace, dir: 'validation')
                }
                stage('Configure and Build Container') {
                    container.build(dir: 'validation', buildScript: 'build.sh', configureScript: 'configure.sh')
                }
                stage('Run Tests') {
                    def names = generate.names()

                    def runParams = [
                            container: [ workspace: workspace, dir: 'validation', name: names.container, image: names.image],
                            test:      [ params: [ packages: "github.com/rancher/tests/validation/${env.TEST_PACKAGE}", cases: env.GOTEST_TESTCASE, tags: env.TAGS ]]
                        ]

                    testContainer = container.run(runParams)
                }

                stage('Report Result') {
                    result.reportFromContainer(workspace: workspace, name: testContainer.container.name, image: testContainer.container.image, dir: 'validation', resultsXML: testContainer.test.resultsXML)
                }
                stage('Remove container') {
                    container.remove([ [name: testContainer.container.name, image: testContainer.container.image] ])
                }
        }
    }
} // node
```

## Importing the Library
There are several ways to import the library in your Jenkinsfile, each with different use cases.

1. Standard Import (Annotation)
Using the `@Library` annotation at the top of your Jenkinsfile. This loads the library for the entire pipeline run.

```groovy
@Library('qa-jenkins-library') _

pipeline {
    // ... your pipeline stages
}
```

The underscore (`_`) is a special character that tells Jenkins to **import all the functions from this library** into the main script's scope. This allows you to call them directly, like runLinter() or goTest().

2. Dynamic Import
You can also load the library dynamically within any stage using the library step. This is useful for more advanced cases, such as specifying a version or using a dynamically named branch.

Specifying a Version or Branch:

You can target a specific branch, tag, or commit hash. 

```groovy
// Use the 'master' branch instead of the configured default
library 'qa-jenkins-library@master'

// Use a feature branch to test new changes
library 'qa-jenkins-library@feature/my-new-function'
```

**Using a Variable for the Version:**

Since library is a regular step, the version can be computed from a variable or parameter, which is **not possible** with the static @Library annotation.

```groovy
// Dynamically use the branch of the job itself
library "qa-jenkins-library@$BRANCH_NAME"
```

3. Namespace Import
Instead of importing functions into the global scope with `_`, you can assign the library to a variable. This namespaces all the functions, which can prevent naming conflicts.

```groovy
// Assign the library to a variable named 'qa'
def qa = library('qa-jenkins-library')

pipeline {
    agent any
    stages {
        stage('Test') {
            steps {
                // Call functions using the variable as a namespace
                qa.runLinter()
                qa.goTest()
            }
        }
    }
}
```