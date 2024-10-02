package com.ido.pipeline.languageBase


import com.ido.pipeline.builderBase.MacosPipeline

/**
 * @author xinnj
 */
abstract class XcodePipeline extends MacosPipeline {
    XcodePipeline(Object steps) {
        super(steps)
    }

    @Override
    def prepare() {
        super.prepare()

        steps.sh """${config.debugSh}
            sudo xcode-select -s "${config.xcode.path}/Contents/Developer"
        """
    }

    @Override
    def ut() {
        if (!config.context.ut.enabled) {
            return
        }

        if (!config.context.ut.scheme) {
            steps.error "ut.scheme is empty!"
        }

        String cmdBuildFile = ""
        if (config.context.buildFile.endsWith('.xcworkspace')) {
            cmdBuildFile = "-workspace ${config.context.buildFile}"
        } else if (config.context.buildFile.endsWith('.xcodeproj')) {
            cmdBuildFile = "-project ${config.context.buildFile}"
        } else {
            steps.error "Can't recognize type of: ${config.context.buildFile}"
        }

        String cmdAuth = ""
        if (config.context.ut.signStyle.toLowerCase() == 'automatic') {
            cmdAuth = "-authenticationKeyPath \${authKeyPath} \
                           -authenticationKeyID ${config.xcode.authenticationKey.ID} \
                           -authenticationKeyIssuerID ${config.xcode.authenticationKey.IssuerID} \
                           -allowProvisioningUpdates"
        }

        String cmdXcconfig = ""
        if (config.context.ut.xcconfig) {
            cmdXcconfig = "-xcconfig ${config.context.ut.xcconfig}"
        }

        steps.withCredentials([steps.usernamePassword(credentialsId: config.macos.loginCredentialId, passwordVariable: 'password', usernameVariable: 'username'),
                               steps.file(credentialsId: config.xcode.authenticationKey.keyFileCredentialId, variable: 'authKeyPath')]) {
            steps.sh """${config.debugSh}
                security unlock-keychain -p \${password}
                security set-keychain-settings -lut 21600 login.keychain
    
                cd "${steps.WORKSPACE}/${config.srcRootPath}"
           
                xcodebuild test \
                  ${cmdBuildFile} \
                  -scheme ${config.context.ut.scheme} \
                  ${cmdXcconfig} \
                  ${cmdAuth} -quiet
           """
        }
    }

    @Override
    def codeAnalysis() {
        if (!config.context.codeAnalysisEnabled) {
            return
        }

        steps.withSonarQubeEnv(config.context.sonarqubeServerName) {
            steps.sh """${config.debugSh}
                cd "${config.srcRootPath}"
                sonar-scanner -Dsonar.projectKey=${config.productName} -Dsonar.sourceEncoding=UTF-8
            """
        }

        if (config.context.qualityGateEnabled) {
            steps.timeout(time: config.context.sonarqubeTimeoutMinutes, unit: 'MINUTES') {
                def qg = steps.waitForQualityGate()
                if (qg.status != 'OK') {
                    steps.error "Quality gate failure: ${qg.status}"
                }
            }
        }
    }
}
