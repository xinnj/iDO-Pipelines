package com.ido.pipeline.sdk

import com.ido.pipeline.archiver.FileArchiver
import com.ido.pipeline.languageBase.XcodePipeline

/**
 * @author xinnj
 */
class IosSdkPipeline extends XcodePipeline {
    String category = "ios"
    FileArchiver fileArchiver

    IosSdkPipeline(Object steps) {
        super(steps)

        this.useK8sAgent = false
        this.nodeName = "macos"
    }

    @Override
    def prepare() {
        super.prepare()
        fileArchiver = new FileArchiver(steps, config)

        if (!config.ios.buildFile) {
            steps.error "buildFile is empty!"
        }

        if (!config.ios.buildOptions) {
            steps.error "buildOptions is empty!"
        }

        config.put("context", config.ios)
        config.parallelUtAnalysis = true
    }

    @Override
    def build() {
        String cmdBuildFile = ""
        if (config.ios.buildFile.endsWith('.xcworkspace')) {
            cmdBuildFile = "-workspace ${config.ios.buildFile}"
        } else if (config.ios.buildFile.endsWith('.xcodeproj')) {
            cmdBuildFile = "-project ${config.ios.buildFile}"
        } else {
            steps.error "Can't recognize type of: ${config.ios.buildFile}"
        }

        def buildTypes = [:]

        config.ios.buildOptions.each {
            String cmdAuth = ""
            if (it.signStyle.toLowerCase() == 'automatic') {
                cmdAuth = "-authenticationKeyPath \${authKeyPath} \
                               -authenticationKeyID ${config.xcode.authenticationKey.ID} \
                               -authenticationKeyIssuerID ${config.xcode.authenticationKey.IssuerID} \
                               -allowProvisioningUpdates"
            }

            String cmdXcconfig = ""
            if (!it.xcconfig) {
                it.xcconfig = ""
            } else {
                cmdXcconfig = "-xcconfig ${it.xcconfig}"
            }

            if (!it.otherParameters) {
                it.otherParameters = ""
            }

            String buildType = "${it.scheme}:${it.configuration}:${it.signStyle}:${it.xcconfig}"

            // Build if not executed
            if (!buildTypes.containsKey(buildType)) {
                steps.withCredentials([steps.usernamePassword(credentialsId: config.macos.loginCredentialId, passwordVariable: 'password', usernameVariable: 'username'),
                                       steps.file(credentialsId: config.xcode.authenticationKey.keyFileCredentialId, variable: 'authKeyPath')]) {
                    String sdkFilenamePrefix = "${config.productName}-${category}-${it.configuration.toLowerCase()}-${config.version}"
                    steps.sh """${config.debugSh}
                        security unlock-keychain -p \${password}
                        security set-keychain-settings -lut 21600 login.keychain
    
                        cd "${steps.WORKSPACE}/${config.srcRootPath}"
            
                        xcodebuild build \
                          ${cmdBuildFile} \
                          -scheme ${it.scheme} \
                          -configuration ${it.configuration} \
                          -derivedDataPath ./build \
                          -sdk ${it.sdk} \
                          ${cmdXcconfig} \
                          ${it.otherParameters} \
                          ${cmdAuth} -quiet

                        mkdir -p "./ido-cluster/outputs"
                        cd "./build/Build/Products/${it.configuration}-${it.sdk}/"
                        zip -rq "${steps.WORKSPACE}/${config.srcRootPath}/ido-cluster/outputs/${sdkFilenamePrefix}.zip" ./
                    """
                }
                buildTypes.put(buildType, it.name)
            }
        }

    }

    @Override
    def archive() {
        String uploadUrl = "${config.fileServer.uploadUrl}${config.fileServer.uploadRootPath}/" +
                "${config._system.sdk.rootPath}/${config.productName}/${config.branch}/${category}"

        def sdkDownloader = new SdkDownloader(steps, config)

        config.ios.buildOptions.each {
            sdkDownloader.genSdkLatestInfo(category, it.configuration.toLowerCase())
        }

        fileArchiver.upload(uploadUrl, "${config.srcRootPath}/ido-cluster/outputs")
    }
}
