package com.ido.pipeline

import jenkins.model.*
import java.util.Date.*
import java.util.TimeZone.*


/**
 * @author xinnj
 */
public class Utils {
    @SuppressWarnings('GroovyAssignabilityCheck')
    static Map setDefault(Map config, steps) {
        Map teamCustomer, systemCustomer
        try {
            teamCustomer = steps.readYaml(text: steps.libraryResource('customer/team.yaml'))
        } catch (Exception ignored) {
        }
        try {
            systemCustomer = steps.readYaml(text: steps.libraryResource('customer/system.yaml'))
        } catch (Exception ignored) {
        }

        Map team = steps.readYaml(text: steps.libraryResource('config/team.yaml'))
        Map system = steps.readYaml(text: steps.libraryResource('config/system.yaml'))


        Map merged = deepMerge(team, teamCustomer)
        merged = deepMerge(merged, config)
        merged._system = deepMerge(system, systemCustomer)
        return merged
    }

    // Refer to: https://e.printstacktrace.blog/how-to-merge-two-maps-in-groovy/
    static Map deepMerge(Map lhs, Map rhs) {
        if (lhs == null) {
            return rhs
        }
        if (rhs == null) {
            return lhs
        }

        return rhs.inject(lhs.clone()) { map, entry ->
            if (map[entry.key] instanceof Map && entry.value instanceof Map) {
                map[entry.key] = deepMerge(map[entry.key], entry.value)
            } else if (map[entry.key] instanceof Collection && entry.value instanceof Collection) {
                map[entry.key] += entry.value
            } else {
                map[entry.key] = entry.value
            }
            return map
        }
    }

    static String getBranchName(steps) {
        steps.echo "BRANCH_NAME: " + steps.env.BRANCH_NAME

        if (steps.env.BRANCH_NAME != null) {
            return steps.env.BRANCH_NAME.split("/")[0]
        }

        return 'null'
    }

    static String getGitCommitVersion(steps) {
        if (steps.isUnix()) {
            steps.sh """#!/bin/sh +x
                git describe --always --long --tags > _gitDesc
            """
        } else {
            steps.bat """
                @echo off
                git describe --always --long --tags > _gitDesc
            """
        }
        String[] strArray = (steps.readFile(file: '_gitDesc', encoding: "UTF-8") as String).trim().split('-')
        return strArray[0] + '.' + strArray[1]
    }

    static copyToFolder(Object steps, String source, String destination, boolean cleanDest) {
        if (steps.isUnix()) {
            steps.sh """
                mkdir -p ${destination}
            """
        } else {
            source = source.replaceAll("/", "\\\\")
            destination = destination.replaceAll("/", "\\\\")
            steps.bat """
                @echo off
                if not exist ${destination} md ${destination}
            """
        }

        if (cleanDest) {
            if (steps.isUnix()) {
                steps.sh """
                rm -rf ${destination}/*
            """
            } else {
                source = source.replaceAll("/", "\\\\")
                destination = destination.replaceAll("/", "\\\\")
                steps.bat """
                @echo off
                del /s /f /q ${destination}\\*
            """
            }
        }

        if (steps.isUnix()) {
            steps.sh """
                cp -af ${source} ${destination}
            """
        } else {
            source = source.replaceAll("/", "\\\\")
            destination = destination.replaceAll("/", "\\\\")
            steps.bat """
                @echo off
                xcopy /E /Y ${source} ${destination}
                if not %errorlevel%==0 exit /B 1
            """
        }
    }

    static notarizeMacApp(Object steps, String inputFile, String macBundleId, String macTeamId) {
        steps.sh """                   
            result=\$(xcrun altool --notarize-app --primary-bundle-id "${macBundleId}" --team-id "${macTeamId}" -u \$userName -p \$password --file ${inputFile})
            IN=\$(echo \${result} | grep -o "RequestUUID = \\S*")
            arrIN=(\${IN// = / })
            RequestUUID=\${arrIN[1]}
            
            while true
            do
                sleep 120
                result=\$(xcrun altool --notarization-info \${RequestUUID} -u \$userName -p \$password)
                IN=\$(echo \${result} | grep -o "Status: \\S*")
                arrIN=(\${IN//: / })
                Status=\${arrIN[1]}
                if [ "\$Status" = "success" ]; then
                    echo "Notarization successful."
                    break
                fi
                if [ "\$Status" = "invalid" ]; then
                    echo "Notarization failed."
                    exit 1
                fi
            done
            
            xcrun stapler staple ${inputFile}
        """
    }

    static String getLatestSuccessBuildVersion(Object steps, String jobName) {
        def builds = Jenkins.instance.getItemByFullName(jobName + '/' + Utils.getBranchName(steps)).builds

        String buildVersion
        def build
        for (int i = 0; i < builds.size(); i++) {
            build = builds.get(i)
            if (build.getResult().toString() == 'SUCCESS') {
                buildVersion = build.getDisplayName()
                steps.echo "buildVersion: ${buildVersion}"
                if (buildVersion.matches("[0-9.]+")) {
                    return buildVersion
                }
            }
        }
    }

    static addGradlePlugin(Object steps, String pluginId, String pluginVersion, String projectPath) {
        String subprojects = ""
        if (steps.fileExists("${projectPath}/settings.gradle")) {
            String settingsGradle = steps.readFile(file: projectPath + "/settings.gradle", encoding: "UTF-8")
            def mInclude = settingsGradle =~ /(?s)include.+/
            if (mInclude) {
                // Is multi-project
                subprojects = """
subprojects {
    apply plugin: '${pluginId}'
}
"""
            }
        }

        String buildGradle = steps.readFile(file: projectPath + "/build.gradle", encoding: "UTF-8")
        def mPlugins = buildGradle =~ /(?s)\n(plugins\s*?\{.*?)}/
        if (mPlugins) {
            // There is a plugins block in the file
            String plugins = mPlugins[0][1]
            def mId = plugins =~ /(?s)${pluginId}/
            if (!mId) {
                // The plugin is not defined
                if (pluginVersion) {
                    plugins = plugins + "\n\tid \"${pluginId}\" version \"${pluginVersion}\"\n}"
                } else {
                    plugins = plugins + "\n\tid \"${pluginId}\"\n}"
                }

                buildGradle = buildGradle.replaceAll("(?s)plugins\\s*?\\{.*?}", plugins)
            }
            mId = null
            mPlugins = null
        } else {
            // There is no plugins block in the file
            mPlugins = null

            String plugins
            // Define the plugins block
            if (pluginVersion) {
                plugins = """
plugins {
  id "${pluginId}" version "${pluginVersion}"
}
"""
            } else {
                plugins = """
plugins {
  id "${pluginId}"
}
"""
            }

            // The plugins block can only be placed after buildscript block
            def mBuildscript = buildGradle =~ /(?s)\n(buildscript\s*?\{.+)$/
            if (mBuildscript) {
                // buildscript block exists
                String restStr = mBuildscript[0][1]
                String beforeBuildscriptBlock = buildGradle.substring(0, mBuildscript.start())

                char[] restChars = restStr.toCharArray()

                int leftNum = 0
                int rightNum = 0
                String buildscriptBlock = ""
                String afterBuildscriptBlock = ""
                int n = restChars.length
                for (int i = 0; i < n; i++) {
                    if (restChars[i] == '{') {
                        leftNum++
                    }
                    if (restChars[i] == '}') {
                        rightNum++
                    }

                    if (leftNum != 0 && leftNum == rightNum) {
                        buildscriptBlock = restStr.substring(0, i + 1)
                        afterBuildscriptBlock = restStr.substring(i + 2, n)
                        break
                    }
                }

                buildGradle = beforeBuildscriptBlock + "\n" + buildscriptBlock + "\n" + plugins + "\n" + subprojects + "\n" + afterBuildscriptBlock
            } else {
                // Put the plugins block at the beginning
                buildGradle = plugins + "\n" + buildGradle
            }
            mBuildscript = null
        }
        steps.writeFile(file: "${projectPath}/build.gradle-${pluginId}", text: buildGradle, encoding: 'UTF-8')
    }

    static String replaceImageMirror(Map mirrors, String original) {
        mirrors.each { k, v ->
            L:
            {
                original = original.replaceAll(k, v)
            }
        }
        return original
    }

    static boolean isDirectory(Object steps, def providedPath) {
        // handle case where a File, nio Path, FileWrapper, etc object is passed
        String path = providedPath.toString()

        // Search for this path as a glob - it will find files beneath a dir
        // and will not report directories
        def files = steps.findFiles(glob: path)

        // check for path found in list then it is a file
        for (def file in files) {
            if ("${file.path}" == path) {
                return false
            }
        }

        return true
    }

    static boolean updateSdkInfo(Object steps, Map config) {
        if (!config.sdk.autoUpdateSdkInfo) {
            return false
        }

        String category = config.category
        if (!category) {
            steps.error "category is empty!"
        }

        String buildTags = category
        String branch = getBranchName(steps)

        String jsonFile
        if (config.sdkInfoFile != null && config.sdkInfoFile.trim() != "") {
            jsonFile = "${config.srcPath}/${config.sdkInfoFile}"
        } else {
            jsonFile = "${config.srcPath}/sdk-info-${category}.json"
        }
        steps.echo "SDK info file: " + jsonFile

        def sdkInfo = steps.readJSON(file: jsonFile)
        if (sdkInfo == null) {
            steps.error "Can't find sdk info file!"
        }

        Map latestInfo
        Boolean modified = false

        if (config.buildTags != null && ((String) config.buildTags).trim() != "") {
            buildTags = buildTags + "&" + ((String) config.buildTags).trim()
        }
        String commitMessage = "[[${buildTags}]]Update SDK Info: "


        for (onePackage in sdkInfo.packages) {
            steps.echo onePackage.name

            String type = onePackage.type
            if (!type) {
                type = "release"
            }

            String latestInfoUrl = "${config.fileServer.downloadUrl}/${config._system.sdk.rootPath}/${config.productName}/" +
                    "${config.sdk.sdkBranch}/${category}/${config.productName}-${category}-${type}-latest.json"
            String latestInfoFile = "${config.productName}-${category}-${type}-latest.json"
            steps.fileOperations([steps.fileDownloadOperation(targetFileName: latestInfoFile, targetLocation: "${config.srcRootPath}/", url: latestInfoUrl)])

            if (steps.fileExists("${config.srcRootPath}/${latestInfoFile}")) {
                latestInfo = steps.readJSON(file: "${config.srcRootPath}/${latestInfoFile}")

                if (onePackage.version != "latest") {
                    Boolean hashChanged = false
                    if (onePackage.sha1 == null || onePackage.sha1 != latestInfo.sha1) {
                        hashChanged = true
                    }

                    if (onePackage.version == null || onePackage.version != latestInfo.version || hashChanged) {
                        latestInfo.each { k, v ->
                            L:
                            {
                                if (v == null) {
                                    onePackage.put(k, "")
                                } else {
                                    onePackage.put(k, v)
                                }
                            }
                        }
                        modified = true

                        commitMessage = commitMessage + "{" + onePackage.name + "|" + latestInfo.version + "|" + latestInfo.author + "|" + latestInfo.message + "}"
                        steps.echo onePackage.name + " version changed from: " + onePackage.version + ", to: " + latestInfo.version + ", message: " + latestInfo.message
                    }
                }
            }
        }

        if (modified) {
            commitMessage = commitMessage.replaceAll("(\\n|\\r)", " ").replaceAll("\"", "'")

            steps.echo "Updated SDK Info: " + sdkInfo
            steps.writeJSON(file: jsonFile, json: sdkInfo, pretty: 4)

            if (steps.isUnix()) {
                steps.withCredentials([steps.sshUserPrivateKey(credentialsId: config.gitCredentialsId, keyFileVariable: 'SSH_KEY', usernameVariable: 'SSH_USER')]) {
                    steps.sh """${config.debugSh}
                        export GIT_SSH_COMMAND=\"ssh -o StrictHostKeyChecking=no -i \${SSH_KEY}\"
                        git config --global user.email "builder@autoupdate"
                        git config --global user.name "builder"
                        git add ${jsonFile}
                        git commit -m "${commitMessage}"
                        git pull origin ${branch} --rebase
                        git push origin HEAD:${branch}
                        if [ \$? -ne 0 ]; then
                            echo Retry git push...
                            git pull origin ${branch} --rebase
                            git push origin HEAD:${branch}
                        fi
                    """
                }
//                steps.withCredentials([steps.gitUsernamePassword(credentialsId: config.gitCredentialsId)]) {
//                    steps.sh """${config.debugSh}
//                        git config --global user.email "builder@autoupdate"
//                        git config --global user.name "builder"
//                        git add ${jsonFile}
//                        git commit -m "${commitMessage}"
//                        git pull origin ${branch} --rebase
//                        git push origin HEAD:${branch} || git_push_error=1
//                        if [ \$? -ne 1 ]; then
//                            echo "Retry git push..."
//                            git pull origin ${branch} --rebase
//                            git push origin HEAD:${branch}
//                        fi
//                    """
//                }
            } else {
                steps.withCredentials([steps.sshUserPrivateKey(credentialsId: config.gitCredentialsId, keyFileVariable: 'SSH_KEY', usernameVariable: 'SSH_USER')]) {
                    steps.pwsh """${config.debugPwsh}
                        GIT_SSH_COMMAND=\"ssh -o StrictHostKeyChecking=no -i \${SSH_KEY}\"
                        git config --global user.email "builder@autoupdate"
                        git config --global user.name "builder"
                        git add ${jsonFile}
                        git commit -m "${commitMessage}"
                        git pull origin ${branch} --rebase
                        git push origin HEAD:${branch}
                        if (-not\$?)
                        {
                            echo Retry git push...
                            git pull origin ${branch} --rebase
                            git push origin HEAD:${branch}
                        }
                    """
                }
//                steps.withCredentials([steps.gitUsernamePassword(credentialsId: config.gitCredentialsId)]) {
//                    steps.pwsh """${config.debugPwsh}
//                        git config --global user.email "builder@autoupdate"
//                        git config --global user.name "builder"
//                        git add ${jsonFile}
//                        git commit -m "${commitMessage}"
//                        git pull origin ${branch} --rebase
//                        git push origin HEAD:${branch}
//                        if (-not\$?)
//                        {
//                            echo Retry git push...
//                            git pull origin ${branch} --rebase
//                            git push origin HEAD:${branch}
//                        }
//                    """
//                }
            }
        }
        return modified
    }

    static genSdkLatestInfo(Object steps, Map config, String category, String type) {
        String sdkFileName = "${config.productName}-${category}-${type}-${config.version}.zip"

        String sha1 = steps.sha1("${config.srcRootPath}/ido-cluster/outputs/${sdkFileName}")

        String commitMessage, commitAuthor
        if (steps.isUnix()) {
            commitMessage = steps.sh(returnStdout: true, script: """${config.debugSh}
                git log --format=%an -n 1
            """).trim()
            commitAuthor = steps.sh(returnStdout: true, script: """${config.debugSh}
                git log --format=%an -n 1
            """).trim()
        } else {
            commitMessage = steps.pwsh(returnStdout: true, script: """${config.debugPwsh}
                git log --format=%an -n 1
            """).trim()
            commitAuthor = steps.pwsh(returnStdout: true, script: """${config.debugPwsh}
                git log --format=%an -n 1
            """).trim()
        }

        Map sdkInfo = [
                "name"   : config.productName,
                "version": config.version,
                "message": commitMessage,
                "author" : commitAuthor,
                "sha1"   : sha1
        ]

        steps.echo "SDK Info: " + sdkInfo
        String jsonFile = "${config.srcRootPath}/ido-cluster/outputs/${config.productName}-${category}-${type}-latest.json"
        steps.writeJSON(file: jsonFile, json: sdkInfo, pretty: 4)
    }
}