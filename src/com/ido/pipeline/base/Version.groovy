package com.ido.pipeline.base

/**
 * @author xinnj
 */
class Version {
    String getVersion(Object steps, Map config) {
        switch (config.versionMethod) {
            case "GIT_DESCRIBE":
                String versionFull
                steps.dir(config.versionRootPath) {
                    if (steps.isUnix()) {
                        versionFull = steps.sh(returnStdout: true, script: """${config.debugSh}
git describe --always --long --tags
""")
                    } else {
                        versionFull = steps.pwsh(returnStdout: true, script: """${config.debugPwsh}
git describe --always --long --tags
""")
                    }
                }
                steps.echo "versionFull: " +  versionFull
                String[] verArray = versionFull.trim().split('-')
                return (verArray[0] + '.' + verArray[1]) as String
                break
            case "GIT_FILE":
                return (steps.readFile(file: config.versionFile, encoding: "UTF-8")).trim()
                break
            case "JENKINS_BUILD_NUMBER":
                return steps.currentBuild.id
                break
            default:
                steps.error "config.versioning.method: ${config.versioning.method} is not recoganized!"
        }
    }
}
