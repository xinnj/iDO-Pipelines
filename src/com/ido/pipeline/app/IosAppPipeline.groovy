package com.ido.pipeline.app

import com.ido.pipeline.Utils
import com.ido.pipeline.archiver.FileArchiver
import com.ido.pipeline.languageBase.XcodePipeline

/**
 * @author xinnj
 */
class IosAppPipeline extends XcodePipeline {
    FileArchiver fileArchiver

    IosAppPipeline(Object steps) {
        super(steps)
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
    def scm() {
        super.scm()

        steps.container('builder') {
            config.ios.certificateCredentialIds.each {
                steps.withCredentials([steps.usernamePassword(credentialsId: config.xcode.loginCredentialId, passwordVariable: 'password', usernameVariable: 'username'),
                                       steps.certificate(credentialsId: it, keystoreVariable: 'keyStore', passwordVariable: 'keyPass')]) {
                    steps.sh """${config.debugSh}
                        ssh remote-host /bin/sh <<EOF
                            ${config.debugSh}
                            set -euao pipefail
                            cd "${steps.WORKSPACE}/${config.srcRootPath}"
                            security unlock-keychain -p \${password}
                            security import "\${keyStore}" -A -f pkcs12 -P \${keyPass}
                            security set-key-partition-list -S apple-tool:,apple:,codesign: -s -k \${password} login.keychain > /dev/null
EOF
                    """
                }
            }

            config.ios.profiles.each {
                steps.sh """${config.debugSh}
                    ssh remote-host /bin/sh <<EOF
                        ${config.debugSh}
                        set -euao pipefail
                        cd "${steps.WORKSPACE}/${config.srcRootPath}"
                        uuid=`grep UUID -A1 -a ${it} | grep -io "[-A-F0-9]\\{36\\}"`
                        cp -f ${it} ~/Library/MobileDevice/Provisioning\\ Profiles/\${uuid}.mobileprovision
EOF
                """
            }
        }
    }

    @Override
    def build() {
        String newFileName = fileArchiver.getFileName()
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

                String buildType = "${it.scheme}:${it.configuration}:${it.signStyle}:${it.xcconfig}"

                // Archive if not executed
                if (!buildTypes.containsKey(buildType)) {
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
    
                                mkdir -p ido-cluster/outputs/files
                                rm -f ido-cluster/outputs/files/*
            
                                xcodebuild archive \
                                  -packageCachePath \${SPM_CACHE_DIR} \
                                  ${cmdBuildFile} \
                                  -scheme ${it.scheme} \
                                  -configuration ${it.configuration} \
                                  -archivePath ./build/${it.name}/${config.productName}.xcarchive \
                                  -destination 'generic/platform=iOS' \
                                  ${cmdXcconfig} \
                                  ${cmdAuth} -quiet
EOF
                        """
                    }
                    buildTypes.put(buildType, it.name)
                }

                // Export ipa
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
            
                                xcodebuild -exportArchive \
                                  -packageCachePath \${SPM_CACHE_DIR} \
                                  -archivePath ./build/${buildTypes.get(buildType)}/${config.productName}.xcarchive \
                                  -exportOptionsPlist ${it.exportOptionsPlist} \
                                  -exportPath ./build/${buildTypes.get(buildType)} \
                                  ${cmdXcconfig} \
                                  ${cmdAuth} -quiet
                                  
                                mv -f ./build/${buildTypes.get(buildType)}/*.ipa ido-cluster/outputs/files/${newFileName}-${it.name}.ipa
                                echo "${it.name} ipa exported."
    
                                xcodebuild ${cmdBuildFile} ${cmdXcconfig} -showBuildSettings \
                                  | awk -F ' = ' '/PRODUCT_BUNDLE_IDENTIFIER/ { print \\\$2 }' > ido-cluster/_PRODUCT_BUNDLE_IDENTIFIER
                                
                                plutil -p ./build/${buildTypes.get(buildType)}/${config.productName}.xcarchive/Info.plist \
                                  | awk -F '"' '/CFBundleShortVersionString/ { print \\\$4 }' > ido-cluster/_BUNDLE_VERSION
EOF
                        """
                }
            }
        }
    }

    @Override
    def archive() {
        String newFileName = fileArchiver.getFileName()
        steps.container('uploader') {
            String uploadUrl = "${config.fileServer.uploadUrl}${config.fileServer.uploadRootPath}${config.productName}/" +
                    Utils.getBranchName(steps) + "/ios"

            steps.sh """${config.debugSh}
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

                steps.sh """${config.debugSh}
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

                    echo "plistUrl: ${plistUrl}"
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

            fileArchiver.upload(uploadUrl, "${config.srcRootPath}/ido-cluster/outputs")
        }
    }
}
