package com.ido.pipeline.languageBase

import com.ido.pipeline.Utils
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

        steps.container('builder') {
            steps.sh """${config.debugSh}
                ssh -q remote-host /bin/sh <<EOF
                    ${config.debugSh}
                    set -euao pipefail
                    
                    sudo xcode-select -s "${config.xcode.path}/Contents/Developer"
EOF
            """
        }
    }

    @Override
    def ut() {
        if (!config.context.ut.enabled) {
            return
        }

        if (!config.context.ut.scheme) {
            steps.error "ut.scheme is empty!"
        }

        steps.container('builder') {
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
                                   steps.file(credentialsId: config.xcode.authenticationKey.keyFileCredentialId,
                                           variable: 'authKeyPath')]) {
                steps.sh """${config.debugSh}
                    ssh remote-host /bin/sh <<EOF
                        ${config.debugSh}
                        set -euao pipefail
                        security unlock-keychain -p \${password}
                        security set-keychain-settings -lut 21600 login.keychain
    
                        cd "${steps.WORKSPACE}/${config.srcRootPath}"
            
                        xcodebuild test \
                          -packageCachePath \${SPM_CACHE_DIR} \
                          ${cmdBuildFile} \
                          -scheme ${config.context.ut.scheme} \
                          ${cmdXcconfig} \
                          ${cmdAuth} -quiet
EOF
                """
            }
        }
    }

    @Override
    def codeAnalysis() {
        if (!config.context.codeAnalysisEnabled) {
            return
        }

        steps.container('sonar-scanner') {
            steps.withSonarQubeEnv(config.context.sonarqubeServerName) {
                steps.sh """${config.debugSh}
                    cd "${config.srcRootPath}"
                    sonar-scanner -Dsonar.projectKey=${config.productName} -Dsonar.sourceEncoding=UTF-8
                """
            }
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
