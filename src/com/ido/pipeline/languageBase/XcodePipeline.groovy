package com.ido.pipeline.languageBase

import com.ido.pipeline.Utils
import com.ido.pipeline.base.BasePipeline

/**
 * @author xinnj
 */
abstract class XcodePipeline extends BasePipeline {
    XcodePipeline(Object steps) {
        super(steps)
    }

    @Override
    Map runPipeline(Map config) {
        steps.withCredentials([steps.usernamePassword(credentialsId: config.xcode.loginCredentialId, passwordVariable: 'password', usernameVariable: 'username')]) {
            if (config.xcode.useRemoteBuilder) {
                steps.lock(label: "macos-builder", quantity: 1, resourceSelectStrategy: "random", variable: "builder") {
                    String remoteHost = steps.env.builder0_host
                    String remotePort = steps.env.builder0_port
                    String username = steps.env.username
                    String password = steps.env.password
                    steps.echo "Locked remote builder: " + steps.env.builder

                    config.podTemplate = steps.libraryResource(resource: "pod-template/macos-remote-builder.yaml", encoding: 'UTF-8')
                            .replaceAll('<builderImage>', config._system.macos.remoteBuilderImage)
                            .replaceAll('<REMOTE_HOST>', remoteHost)
                            .replaceAll('<REMOTE_PORT>', remotePort)
                            .replaceAll('<USERNAME>', username)
                            .replaceAll('<PASSWORD>', password)
                }
            } else {
                String username = steps.env.username
                String password = steps.env.password
                config.podTemplate = steps.libraryResource(resource: "pod-template/macos-builder.yaml", encoding: 'UTF-8')
                        .replaceAll('<builderImage>', config._system.macos.builderImage)
                        .replaceAll('<macosImage>', config.xcode.macosImage)
                        .replaceAll('<USERNAME>', username)
                        .replaceAll('<PASSWORD>', password)
            }
        }

        return super.runPipeline(config)
    }

    @Override
    def prepare() {
        super.prepare()

        String smbServerAddress
        steps.container('builder') {
            if (config.xcode.useRemoteBuilder) {
                smbServerAddress = "//${config._system.smbServer.user}:${config._system.smbServer.password}@${config._system.smbServer.external}/${config._system.smbServer.shareName}"
                steps.sh """${config.debugSh}
                    currentHome=\$HOME
                    sudo -- sh -c "cat \${currentHome}/hosts >> /etc/hosts"
                """
            } else {
                smbServerAddress = "//${config._system.smbServer.user}:${config._system.smbServer.password}@${config._system.smbServer.internal}/${config._system.smbServer.shareName}"
                steps.sh """${config.debugSh}
                    if [[ \$(grep -E -c '(svm|vmx)' /proc/cpuinfo) -le 0 ]]; then
                        echo KVM not possible on this host
                        exit 1
                    fi
                    
                    sudo -- sh -c "echo '127.0.0.1 remote-host' >> /etc/hosts"
                """
            }

            String useCocoapods = (config.xcode.useCocoapods as Boolean).toString().toLowerCase()
            steps.sh """${config.debugSh}
                ssh -q remote-host /bin/sh <<EOF
                    ${config.debugSh}
                    set -euao pipefail

                    if [[ ! -d "~/agent/workspace" ]]; then
                        mkdir -p ~/agent
                        mount -t smbfs ${smbServerAddress} ~/agent
                    fi

                    mkdir -p \${SPM_CACHE_DIR}
                    mkdir -p \${CP_HOME_DIR}
                    
                    sudo xcode-select -s "${config.xcode.path}/Contents/Developer"

                    if [ "${useCocoapods}" = "true" ]; then
                        export LANG=en_US.UTF-8
                        export CP_HOME_DIR=\${CP_HOME_DIR}
                        if [ ! -d "\${CP_HOME_DIR}/repos/trunk" ]; then
                            /usr/local/bin/pod --version
                            mkdir -p "\${CP_HOME_DIR}/repos"
                            cd "\${CP_HOME_DIR}/repos"
                            git clone ${config._system.macos.cocoapodsRepoUrl} trunk
                            /usr/local/bin/pod setup
                        fi
                        /usr/local/bin/pod install
                    fi
EOF
            """
        }
    }

    @Override
    def afterScm() {
        steps.container('builder') {
            steps.withCredentials([steps.usernamePassword(credentialsId: config.xcode.loginCredentialId, passwordVariable: 'password', usernameVariable: 'username')]) {
                steps.sh """${config.debugSh}
                    ssh remote-host /bin/sh <<EOF
                        ${config.debugSh}
                        set -euao pipefail
                        security unlock-keychain -p \${password}
                        security set-keychain-settings -lut 21600 login.keychain
    
                        set -x
                        cd "${steps.WORKSPACE}/${config.srcRootPath}"
                        sh "${config.customerBuildScript.afterScm}"
EOF
                """
            }
        }
    }

    @Override
    def versioning() {
        super.versioning()

        String branch = Utils.getBranchName(steps)
        steps.container('builder') {
            steps.sh """${config.debugSh}
                ssh remote-host /bin/sh <<EOF
                    ${config.debugSh}
                    rm -f ./~ido-cluster-env.sh
                    touch ./~ido-cluster-env.sh
                    echo "export CI_PRODUCTNAME=${config.productName}" >> ./~ido-cluster-env.sh
                    echo "export CI_VERSION=${config.version}" >> ./~ido-cluster-env.sh
                    echo "export CI_BRANCH=${branch}" >> ./~ido-cluster-env.sh
EOF
            """
        }

    }

    @Override
    def afterVersioning() {
        steps.container('builder') {
            steps.withCredentials([steps.usernamePassword(credentialsId: config.xcode.loginCredentialId, passwordVariable: 'password', usernameVariable: 'username')]) {
                steps.sh """${config.debugSh}
                    ssh remote-host /bin/sh <<EOF
                        ${config.debugSh}
                        set -euao pipefail
                        . ./~ido-cluster-env.sh
                        security unlock-keychain -p \${password}
                        security set-keychain-settings -lut 21600 login.keychain
    
                        set -x
                        cd "${steps.WORKSPACE}/${config.srcRootPath}"
                        sh "${config.customerBuildScript.afterVersioning}"
EOF
                """

                config.version = steps.readFile(file: "${config.srcRootPath}/ido-cluster/_version", encoding: "UTF-8").trim()

                steps.sh """${config.debugSh}
                    ssh remote-host /bin/sh <<EOF
                        ${config.debugSh}
                        sed -i -e "s/export CI_VERSION=.*/export CI_VERSION=${config.version}/" ./~ido-cluster-env.sh
EOF
                    """
            }
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

            steps.withCredentials([steps.usernamePassword(credentialsId: config.xcode.loginCredentialId, passwordVariable: 'password', usernameVariable: 'username'),
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

    @Override
    def beforeBuild() {
        steps.container('builder') {
            steps.withCredentials([steps.usernamePassword(credentialsId: config.xcode.loginCredentialId, passwordVariable: 'password', usernameVariable: 'username')]) {
                steps.sh """${config.debugSh}
                    ssh remote-host /bin/sh <<EOF
                        ${config.debugSh}
                        set -euao pipefail
                        . ./~ido-cluster-env.sh
                        security unlock-keychain -p \${password}
                        security set-keychain-settings -lut 21600 login.keychain

                        set -x
                        cd "${steps.WORKSPACE}/${config.srcRootPath}"
                        sh "./${config.customerBuildScript.beforeBuild}"
EOF
                """
            }
        }
    }

    @Override
    Boolean customerBuild() {
        steps.container('builder') {
            if (steps.fileExists("${steps.WORKSPACE}/${config.srcRootPath}/${config.customerBuildScript.build}")) {
                steps.echo "Execute customer build script: ${config.customerBuildScript.build}"
                steps.withCredentials([steps.usernamePassword(credentialsId: config.xcode.loginCredentialId, passwordVariable: 'password', usernameVariable: 'username')]) {
                    steps.sh """${config.debugSh}
                        ssh remote-host /bin/sh <<EOF
                            ${config.debugSh}
                            set -euao pipefail
                            . ./~ido-cluster-env.sh
                            security unlock-keychain -p \${password}
                            security set-keychain-settings -lut 21600 login.keychain

                            set -x
                            cd "${steps.WORKSPACE}/${config.srcRootPath}"
                            sh "./${config.customerBuildScript.build}"
EOF
                    """
                }
                return true
            } else {
                return false
            }
        }
    }

    @Override
    def afterBuild() {
        steps.container('builder') {
            steps.withCredentials([steps.usernamePassword(credentialsId: config.xcode.loginCredentialId, passwordVariable: 'password', usernameVariable: 'username')]) {
                steps.sh """${config.debugSh}
                    ssh remote-host /bin/sh <<EOF
                        ${config.debugSh}
                        set -euao pipefail
                        . ./~ido-cluster-env.sh
                        security unlock-keychain -p \${password}
                        security set-keychain-settings -lut 21600 login.keychain

                        set -x
                        cd "${steps.WORKSPACE}/${config.srcRootPath}"
                        sh "./${config.customerBuildScript.afterBuild}"
EOF
                """
            }
        }
    }

}
