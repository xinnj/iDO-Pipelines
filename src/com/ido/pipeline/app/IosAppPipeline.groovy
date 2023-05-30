package com.ido.pipeline.app

import com.ido.pipeline.Utils
import com.ido.pipeline.base.Artifact

/**
 * @author xinnj
 */
class IosAppPipeline extends AppPipeline {
    IosAppPipeline(Object steps) {
        super(steps)
    }

    @Override
    Map runPipeline(Map config) {
        config.nodeType = "k8s"
        config.parallelUtAnalysis = true

        String builder = steps.libraryResource(resource: 'pod-template/macos-builder.yaml', encoding: 'UTF-8')
        builder = builder.replaceAll('<builderImage>', config.ios.builderImage)
                .replaceAll('<macosImage>', config.ios.macosImage)
        config.podTemplate = builder

        return super.runPipeline(config)
    }

    @Override
    def prepare() {
        super.prepare()

        if (!config.ios.buildFile) {
            steps.error "buildFile is empty!"
        }

        if (!config.ios.buildOptions) {
            steps.error "buildOptions is empty!"
        }

        if (config.nodeType == "k8s") {
            steps.container('builder') {
                steps.sh """
                    if [ \$(grep -E -c '(svm|vmx)' /proc/cpuinfo) -le 0 ]; then
                        echo KVM not possible on this host
                        exit 1
                    fi
                    
                    ssh 127.0.0.1 /bin/sh << EOF
                        set -euaxo pipefail
                        sudo mkdir -p \${SPM_CACHE_DIR}
                        sudo mkdir -p \${CP_HOME_DIR}
                """
            }

        }
    }

    @Override
    def scm() {
        super.scm()

        steps.container('builder') {
            steps.withCredentials([steps.string(credentialsId: config.ios.keychainCredentialId, variable: 'macKeychain'),
                                   steps.certificate(credentialsId: config.ios.distributionCertCredentialId, keystoreVariable: 'keyStore', passwordVariable: 'keyPass')]) {
                String useCocoapods = (config.ios.useCocoapods as Boolean).toString().toLowerCase()
                steps.sh """
                    ssh 127.0.0.1 /bin/sh << EOF
                        set -euao pipefail
                        sudo xcode-select -s /Applications/Xcode.app/Contents/Developer
                        cd "${steps.WORKSPACE}/${config.srcRootPath}"
                        security unlock-keychain -p \${macKeychain}
                        security import "\${keyStore}" -A -f pkcs12 -P \${keyPass}
                        security set-key-partition-list -S apple-tool:,apple:,codesign: -s -k \${macKeychain} login.keychain > /dev/null
                        
                        set -x
                        export LANG=en_US.UTF-8
                        if [ "${useCocoapods}" = "true" ]; then
                            export CP_HOME_DIR=\${CP_HOME_DIR}
                            if [ ! -d "\${CP_HOME_DIR}/repos/trunk" ]; then
                                /usr/local/bin/pod --version
                                mkdir -p "\${CP_HOME_DIR}/repos"
                                cd "\${CP_HOME_DIR}/repos"
                                git clone ${config._system.cocoapodsRepoUrl} trunk
                                /usr/local/bin/pod setup
                            fi
                            /usr/local/bin/pod install
                        fi
                """
            }

            config.ios.profilesImport.each {
                steps.sh """
                    ssh 127.0.0.1 /bin/sh << EOF
                        set -euao pipefail
                        cd "${steps.WORKSPACE}/${config.srcRootPath}"
                        uuid=`grep UUID -A1 -a ${it} | grep -io "[-A-F0-9]\\{36\\}"`
                        cp -f ${it} ~/Library/MobileDevice/Provisioning\\ Profiles/\${uuid}.mobileprovision
                """
            }
        }
    }

    @Override
    def versioning() {
        super.versioning()

        String branch = Utils.getBranchName(steps)
        steps.container('builder') {
            steps.sh """
                ssh 127.0.0.1 /bin/sh << EOF
                    rm -f ./~ido-cluster-env.sh
                    touch ./~ido-cluster-env.sh
                    echo "export CI_PRODUCTNAME=${config.productName}" >> ./~ido-cluster-env.sh
                    echo "export CI_VERSION=${config.version}" >> ./~ido-cluster-env.sh
                    echo "export CI_BRANCH=${branch}" >> ./~ido-cluster-env.sh
            """
        }

    }

    @Override
    def afterScm() {
        steps.container('builder') {
            steps.withCredentials([steps.string(credentialsId: config.ios.keychainCredentialId, variable: 'macKeychain')]) {
                steps.sh """
                    ssh 127.0.0.1 /bin/sh << EOF
                        set -euao pipefail
                        security unlock-keychain -p \${macKeychain}
                        security set-keychain-settings -lut 21600 login.keychain
                        set -x
    
                        cd "${steps.WORKSPACE}/${config.srcRootPath}"
                        sh "${config.customerBuildScript.afterScm}"
                """

                config.version = steps.readFile(file: "${config.srcRootPath}/ido-cluster/_version", encoding: "UTF-8").trim()

                steps.sh """
                    ssh 127.0.0.1 /bin/sh << EOF
                        sed -i -e "s/export CI_VERSION=.*/export CI_VERSION=${config.version}/" ./~ido-cluster-env.sh
                    """
            }
        }
    }

    @Override
    def ut() {
        if (!config.ios.ut.enabled) {
            return
        }

        if (!config.ios.ut.scheme) {
            steps.error "ut.scheme is empty!"
        }

        steps.container('builder') {
            String cmdBuildFile = ""
            if (config.ios.buildFile.endsWith('.xcworkspace')) {
                cmdBuildFile = "-workspace ${config.ios.buildFile}"
            } else if (config.ios.buildFile.endsWith('.xcodeproj')) {
                cmdBuildFile = "-project ${config.ios.buildFile}"
            } else {
                steps.error "Can't recognize type of: ${config.ios.buildFile}"
            }

            String cmdAuth = ""
            if (config.ios.ut.signStyle.toLowerCase() == 'automatic') {
                cmdAuth = "-authenticationKeyPath \${authKeyPath} \
                           -authenticationKeyID ${config.ios.authenticationKey.ID} \
                           -authenticationKeyIssuerID ${config.ios.authenticationKey.IssuerID} \
                           -allowProvisioningUpdates"
            }

            String cmdXcconfig = ""
            if (config.ios.ut.xcconfig) {
                cmdXcconfig = "-xcconfig ${config.ios.ut.xcconfig}"
            }

            steps.withCredentials([steps.string(credentialsId: config.ios.keychainCredentialId, variable: 'macKeychain'),
                                   steps.file(credentialsId: config.ios.authenticationKey.keyFileCredentialId,
                                           variable: 'authKeyPath')]) {
                steps.sh """
                    ssh 127.0.0.1 /bin/sh << EOF
                        set -euao pipefail
                        security unlock-keychain -p \${macKeychain}
                        security set-keychain-settings -lut 21600 login.keychain
                        set -x
    
                        cd "${steps.WORKSPACE}/${config.srcRootPath}"
            
                        xcodebuild test \
                          -packageCachePath \${SPM_CACHE_DIR} \
                          ${cmdBuildFile} \
                          -scheme ${config.ios.ut.scheme} \
                          ${cmdXcconfig} \
                          ${cmdAuth} -quiet
                """
            }
        }
    }

    @Override
    def codeAnalysis() {
        if (!config.ios.codeAnalysisEnabled) {
            return
        }

        steps.container('sonar-scanner') {
            steps.withSonarQubeEnv(config.ios.sonarqubeServerName) {
                steps.sh """
                    cd "${config.srcRootPath}"
                    sonar-scanner -Dsonar.projectKey=${config.productName} -Dsonar.sourceEncoding=UTF-8
                """
            }
        }

        if (config.ios.qualityGateEnabled) {
            steps.timeout(time: config.ios.sonarqubeTimeoutMinutes, unit: 'MINUTES') {
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
            String branch = Utils.getBranchName(steps)
            steps.withCredentials([steps.string(credentialsId: config.ios.keychainCredentialId, variable: 'macKeychain')]) {
                steps.sh """
                    ssh 127.0.0.1 /bin/sh << EOF
                        set -euao pipefail
                        . ./~ido-cluster-env.sh
                        security unlock-keychain -p \${macKeychain}
                        security set-keychain-settings -lut 21600 login.keychain
                        set -x
    
                        cd "${steps.WORKSPACE}/${config.srcRootPath}"
                        sh "./${config.customerBuildScript.beforeBuild}"
                """
            }
        }
    }

    @Override
    Boolean customerBuild() {
        steps.container('builder') {
            if (steps.fileExists("${steps.WORKSPACE}/${config.srcRootPath}/${config.customerBuildScript.build}")) {
                steps.echo "Execute customer build script: ${config.customerBuildScript.build}"
                String branch = Utils.getBranchName(steps)
                steps.withCredentials([steps.string(credentialsId: config.ios.keychainCredentialId, variable: 'macKeychain')]) {
                    steps.sh """
                        ssh 127.0.0.1 /bin/sh << EOF
                            set -euao pipefail
                            . ./~ido-cluster-env.sh
                            security unlock-keychain -p \${macKeychain}
                            security set-keychain-settings -lut 21600 login.keychain
                            set -x
        
                            cd "${steps.WORKSPACE}/${config.srcRootPath}"
                            sh "./${config.customerBuildScript.build}"
                    """
                }
            }
        }
    }

    @Override
    def build() {
        if (this.customerBuild()) {
            return
        }

        String newFileName = this.getFileName()
        steps.container('builder') {
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
                               -authenticationKeyID ${config.ios.authenticationKey.ID} \
                               -authenticationKeyIssuerID ${config.ios.authenticationKey.IssuerID} \
                               -allowProvisioningUpdates"
                }

                String cmdXcconfig = ""
                if (!it.xcconfig) {
                    it.xcconfig = ""
                } else {
                    cmdXcconfig = "-xcconfig ${it.xcconfig}"
                }

                String buildType = "${it.scheme}:${it.configuration}:${it.signStyle}:${it.xcconfig}"

                // Archive if not executed
                if (!buildTypes.containsKey(buildType)) {
                    steps.withCredentials([steps.string(credentialsId: config.ios.keychainCredentialId, variable: 'macKeychain'),
                                           steps.file(credentialsId: config.ios.authenticationKey.keyFileCredentialId,
                                                   variable: 'authKeyPath')]) {
                        steps.sh """
                            ssh 127.0.0.1 /bin/sh << EOF
                                set -euao pipefail
                                security unlock-keychain -p \${macKeychain}
                                security set-keychain-settings -lut 21600 login.keychain
                                set -x
    
                                cd "${steps.WORKSPACE}/${config.srcRootPath}"
    
                                mkdir -p ido-cluster/outputs/files
                                rm -f ido-cluster/outputs/files/*
                                
                                rm -rf ~/build.tmp
                                mkdir -p ~/build.tmp
            
                                xcodebuild archive \
                                  -packageCachePath \${SPM_CACHE_DIR} \
                                  ${cmdBuildFile} \
                                  -scheme ${it.scheme} \
                                  -configuration ${it.configuration} \
                                  -archivePath ~/build.tmp/${it.name}/${config.productName}.xcarchive \
                                  -destination 'generic/platform=iOS' \
                                  ${cmdXcconfig} \
                                  ${cmdAuth} -quiet
                        """
                    }
                    buildTypes.put(buildType, it.name)
                }

                // Export ipa
                steps.withCredentials([steps.string(credentialsId: config.ios.keychainCredentialId, variable: 'macKeychain'),
                                       steps.file(credentialsId: config.ios.authenticationKey.keyFileCredentialId,
                                               variable: 'authKeyPath')]) {
                    steps.sh """
                            ssh 127.0.0.1 /bin/sh << EOF
                                set -euao pipefail
                                security unlock-keychain -p \${macKeychain}
                                security set-keychain-settings -lut 21600 login.keychain
                                set -x
    
                                cd "${steps.WORKSPACE}/${config.srcRootPath}"
            
                                xcodebuild -exportArchive \
                                  -packageCachePath \${SPM_CACHE_DIR} \
                                  -archivePath ~/build.tmp/${buildTypes.get(buildType)}/${config.productName}.xcarchive \
                                  -exportOptionsPlist ${it.exportOptionsPlist} \
                                  -exportPath ~/build.tmp/${buildTypes.get(buildType)} \
                                  ${cmdXcconfig} \
                                  ${cmdAuth} -quiet
                                  
                                mv -f ~/build.tmp/${buildTypes.get(buildType)}/*.ipa ido-cluster/outputs/files/${newFileName}-${it.name}.ipa
                                echo "${it.name} ipa exported."
    
                                xcodebuild ${cmdBuildFile} ${cmdXcconfig} -showBuildSettings \
                                  | awk -F ' = ' '/PRODUCT_BUNDLE_IDENTIFIER/ { print \\\$2 }' > ido-cluster/_PRODUCT_BUNDLE_IDENTIFIER
                                
                                plutil -p ~/build.tmp/${buildTypes.get(buildType)}/${config.productName}.xcarchive/Info.plist \
                                  | awk -F '"' '/CFBundleShortVersionString/ { print \\\$4 }' > ido-cluster/_BUNDLE_VERSION
                        """
                }
            }
        }
    }

    @Override
    def afterBuild() {
        steps.container('builder') {
            String branch = Utils.getBranchName(steps)
            steps.withCredentials([steps.string(credentialsId: config.ios.keychainCredentialId, variable: 'macKeychain')]) {
                steps.sh """
                    ssh 127.0.0.1 /bin/sh << EOF
                        set -euao pipefail
                        . ./~ido-cluster-env.sh
                        security unlock-keychain -p \${macKeychain}
                        security set-keychain-settings -lut 21600 login.keychain
                        set -x
    
                        cd "${steps.WORKSPACE}/${config.srcRootPath}"
                        sh "./${config.customerBuildScript.afterBuild}"
                """
            }
        }
    }

    @Override
    def archive() {
        String newFileName = this.getFileName()
        steps.container('uploader') {
            String uploadUrl = "${config.fileServer.uploadUrl}${config.fileServer.uploadRootPath}${config.productName}/" +
                    Utils.getBranchName(steps) + "/ios"

            steps.sh """
                cd "${config.srcRootPath}/ido-cluster/outputs"
                touch ${newFileName}.html
                echo "<html>" >> ${newFileName}.html
                echo "<meta http-equiv='Content-Type' content='text/html; charset=utf-8' />" >> ${newFileName}.html
            """

            String text = steps.libraryResource('builder/ios-ota-manifest.plist.template')
            steps.writeFile(file: "${config.srcRootPath}/ido-cluster/manifest.plist.template", text: text, encoding: "UTF-8")

            config.ios.buildOptions.each {
                String downloadUrl = "${config.fileServer.downloadUrl}${config.fileServer.uploadRootPath}${config.productName}/" +
                        Utils.getBranchName(steps) + "/ios/files/${newFileName}-${it.name}.ipa"
                String plistUrl = "itms-services://?action=download-manifest&amp;" +
                        "url=${config.fileServer.downloadUrl}/${config.fileServer.uploadRootPath}${config.productName}/" +
                        Utils.getBranchName(steps) + "/ios/files/${newFileName}-manifest-${it.name}.plist"

                steps.sh """
                    cd "${config.srcRootPath}"
                    software_package=${downloadUrl}
                    display_image=${config.ios.downloadDisplayImages.standard}
                    full_size_image=${config.ios.downloadDisplayImages.fullSize}
                    PRODUCT_BUNDLE_IDENTIFIER=\$(cat ido-cluster/_PRODUCT_BUNDLE_IDENTIFIER)
                    BUNDLE_VERSION=\$(cat ido-cluster/_BUNDLE_VERSION)
                    PRODUCT_TITLE=${config.productName}
                    
                    cp -f ido-cluster/manifest.plist.template ido-cluster/outputs/files/${newFileName}-manifest-${it.name}.plist
                    sed -i -e \"s|<software_package>|\${software_package}|g\" \
                           -e \"s|<display_image>|\${display_image}|g\" \
                           -e \"s|<full_size_image>|\${full_size_image}|g\" \
                           -e \"s|<PRODUCT_BUNDLE_IDENTIFIER>|\${PRODUCT_BUNDLE_IDENTIFIER}|g\" \
                           -e \"s|<BUNDLE_VERSION>|\${BUNDLE_VERSION}|g\" \
                           -e \"s|<PRODUCT_TITLE>|\${PRODUCT_TITLE}|g\" \
                      ido-cluster/outputs/files/${newFileName}-manifest-${it.name}.plist

                    cd "${config.srcRootPath}/ido-cluster/outputs"

                    qrencode --output qrcode.png "${plistUrl}"
                    if [ ! -f qrcode.png ]; then
                        echo QR code is not generated!
                        exit 1
                    fi
                    qrcode="\$(cat qrcode.png | base64 )"
                    rm -f qrcode.png
                    
                    echo "<hr><h3>${it.name}</h3>" >> ${newFileName}.html
                    echo "<p><a href='files/${newFileName}-${it.name}.ipa'>${newFileName}-${it.name}.ipa</a>" >> ${newFileName}.html
                    echo "<p><img src='data:image/png;base64,\${qrcode}'/>" >> ${newFileName}.html
                """
            }

            Artifact artifact = new Artifact()
            artifact.uploadToFileServer(steps, uploadUrl, "${config.srcRootPath}/ido-cluster/outputs")
        }
    }

    String getFileName() {
        String branch = Utils.getBranchName(steps)
        return "${config.productName}-${branch}-${config.version}" as String
    }
}
