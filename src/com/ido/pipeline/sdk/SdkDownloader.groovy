package com.ido.pipeline.sdk

import com.ido.pipeline.Utils

class SdkDownloader {
    Object steps
    Map config

    SdkDownloader(Object steps, Map config) {
        this.steps = steps
        this.config = config
    }

    boolean updateSdkInfo() {
        if (!config.sdk.autoUpdateSdkInfo) {
            return false
        }

        String category = config.category
        if (!category) {
            return false
        }

        String jsonFile
        if (config.sdk.sdkInfoFile) {
            jsonFile = "${config.srcRootPath}/${config.sdk.sdkInfoFile}"
        } else {
            jsonFile = "${config.srcRootPath}/sdk-info-${category}.json"
        }

        if (!steps.fileExists(jsonFile)) {
            steps.echo "${jsonFile} not exists."
            return false
        }

        steps.echo "SDK info file: " + jsonFile

        String buildTags = category
        String branch = Utils.getBranchName(steps)
        if (!config.sdk.sdkBranch) {
            config.sdk.sdkBranch = branch
        }

        def sdkInfo = steps.readJSON(file: jsonFile)

        Map latestInfo
        Boolean modified = false

        if (config.buildTags != null && ((String) config.buildTags).trim() != "") {
            buildTags = buildTags + "&" + ((String) config.buildTags).trim()
        }
        String commitMessage = "[[${buildTags}]]Update SDK Info: "


        for (onePackage in sdkInfo.packages) {
            steps.echo onePackage.name
            if (onePackage.version != "latest") {
                String type = onePackage.type
                if (!type) {
                    type = "release"
                }

                String latestInfoUrl = "${config.fileServer.downloadUrl}/${config._system.sdk.rootPath}/${config.productName}/" +
                        "${config.sdk.sdkBranch}/${category}/${config.productName}-${category}-${type}-latest.json"
                String latestInfoFile = "${config.productName}-${category}-${type}-latest.json"
                steps.fileOperations([steps.fileDownloadOperation(targetFileName: latestInfoFile, targetLocation: "${config.srcRootPath}/", url: latestInfoUrl, userName: "", password: "")])

                if (steps.fileExists("${config.srcRootPath}/${latestInfoFile}")) {
                    latestInfo = steps.readJSON(file: "${config.srcRootPath}/${latestInfoFile}")
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

    def genSdkLatestInfo(String category, String type) {
        String sdkFileName = "${config.productName}-${category}-${type}-${config.version}.zip"

        String sha1 = steps.sha1("${config.srcRootPath}/ido-cluster/outputs/${sdkFileName}")

        String commitMessage, commitAuthor
        if (steps.isUnix()) {
            commitMessage = steps.sh(returnStdout: true, script: """${config.debugSh}
                git log --format=%B -n 1
            """).trim()
            commitAuthor = steps.sh(returnStdout: true, script: """${config.debugSh}
                git log --format=%an -n 1
            """).trim()
        } else {
            commitMessage = steps.pwsh(returnStdout: true, script: """${config.debugPwsh}
                git log --format=%B -n 1
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

    def downloadSdk() {
        if (!config.category) {
            return
        }

        String jsonFile
        if (config.sdkInfoFile) {
            jsonFile = "${config.srcRootPath}/${config.sdk.sdkInfoFile}"
        } else {
            jsonFile = "${config.srcRootPath}/sdk-info-${config.category}.json"
        }

        if (!steps.fileExists(jsonFile)) {
            return
        }

        steps.echo "SDK info file: " + jsonFile
        Map sdkInfo = steps.readJSON(file: jsonFile)

        steps.fileOperations([steps.folderCreateOperation("sdk-tmp")])

        List servers = sdkInfo.servers
        String branch = config.sdk.sdkBranch ?: Utils.getBranchName(steps)

        sdkInfo.packages.each {
            String packageName = it.name
            String category = it.category
            String type = it.type ?: "release"

            Boolean useBranch = it.branch ?: true
            String branchDir
            if (useBranch) {
                branchDir = branch + "/"
            } else {
                branchDir = ""
            }

            Boolean downloadLatest = it.version == "latest"
            Map latestInfo
            String version
            String sha1
            if (downloadLatest) {
                latestInfo = getSdkLatestInfo(servers, category, branchDir, packageName, type)
                version = latestInfo.version
                sha1 = latestInfo.sha1
            } else {
                version = it.version
                sha1 = it.sha1
            }

            String folderName = "${packageName}-${category}-${type}-${version}"
            String fileName = "${folderName}.zip"
            List installPath = it.install_path

            getSdk(servers, fileName, packageName, category, branchDir, sha1)
            installSdk(fileName, folderName, installPath)
        }
    }

    private Map getSdkLatestInfo(List servers, String category, String branchDir, String packageName, String type) {
        String sdkLatestInfoFile = "${packageName}-${category}-${type}-latest.json"
        String downloadPath = "/download/${config._system.sdk.rootPath}/${packageName}/${branchDir}${category}/${sdkLatestInfoFile}"

        Boolean downloaded = true
        for (oneServer in servers) {
            try {
                String url = "${oneServer}/${downloadPath}"
                steps.fileOperations([steps.fileDownloadOperation(targetLocation: "sdk-tmp", targetFileName: sdkLatestInfoFile, url: url, userName: '', password: '')])
            } catch (Exception ignored) {
                downloaded = false
            }

            if (downloaded) {
                break
            }
        }

        if (!downloaded) {
            steps.error "Can't download: ${downloadPath}"
        } else {
            steps.echo "Downloaded: ${downloadPath}"
        }

        return steps.readJSON(file: "sdk-tmp/${sdkLatestInfoFile}")
    }

    private getSdk(List servers, String fileName, String packageName, String category, String branchDir, String sha1) {
        String downloadPath = "/download/${config._system.sdk.rootPath}/${packageName}/${branchDir}${category}/${fileName}"

        Boolean downloaded = true
        for (oneServer in servers) {
            try {
                String url = "${oneServer}/${downloadPath}"
                steps.fileOperations([steps.fileDownloadOperation(targetLocation: "sdk-tmp", targetFileName: fileName, url: url, userName: '', password: '')])
            } catch (Exception ignored) {
                downloaded = false
            }

            if (downloaded) {
                if (steps.sha1("sdk-tmp/${fileName}") == sha1) {
                    break
                } else {
                    steps.echo "SHA1 check failed!"
                   downloaded = false
                }
            }
        }

        if (!downloaded) {
            steps.error "Can't download: ${downloadPath}"
        } else {
            steps.echo "Downloaded: ${downloadPath}"
        }
    }

    private installSdk(String fileName, String folderName, List installPath) {
        steps.unzip(quiet: true, zipFile: "sdk-tmp/${fileName}", dir: "sdk-tmp/${folderName}")

        for (onePath in installPath) {
            steps.fileOperations([steps.fileCopyOperation(includes: "sdk-tmp/${folderName}/${onePath.from}", targetLocation: onePath.to, flattenFiles: true)])
            steps.echo "Installed from \"${onePath.from}\" to \"${onePath.to}\""
        }
    }
}