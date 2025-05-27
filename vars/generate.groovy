//  suffix string
def names(String suffix = 'test') {
    def jobName = env.JOB_NAME
    def buildNumber = env.BUILD_NUMBER

    if (jobName.contains('/')) {
        def jobNames = jobName.split('/')

        jobName = jobNames[job_names.size() - 1]
    }

    def containerName = "${jobName}${buildNumber}_test"
    def imageName = "rancher-validation-${jobName}${buildNumber}"

    return [container: containerName, image: imageName]

//Below enables name generation with Integer count param
//requires docker's build.sh changes for name, right now it's not an ENV

// max count is 10
// if (count > 10) {
//     count = 10
// }
// List resourceNames = []
//
//
// for (int i = 1; i <= count; i++) {
//     def containerName = "${jobName}-${buildNumber}-${suffix}-${i}"
//
//     def imageName = "rancher-test-${jobName}-${buildNumber}-${i}"
//
//     resourceNames << [containerName: containerName, imageName: imageName]
// }
//
// return resourceNames
}
