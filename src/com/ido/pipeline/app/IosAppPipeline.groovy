package com.ido.pipeline.app


import com.ido.pipeline.archiver.FileArchiver
import com.ido.pipeline.languageBase.XcodePipeline

/**
 * @author xinnj
 */
class IosAppPipeline extends XcodePipeline {
    FileArchiver fileArchiver

    IosAppPipeline(Object steps) {
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
    def scm() {
        super.scm()

        config.ios.certificateCredentialIds.each {
            steps.withCredentials([steps.usernamePassword(credentialsId: config.macos.loginCredentialId, passwordVariable: 'password', usernameVariable: 'username'),
                                   steps.certificate(credentialsId: it, keystoreVariable: 'keyStore', passwordVariable: 'keyPass')]) {
                steps.sh """${config.debugSh}
                    cd "${steps.WORKSPACE}/${config.srcRootPath}"
                    security unlock-keychain -p \${password}
                    security import "\${keyStore}" -A -f pkcs12 -P \${keyPass}
                    security set-key-partition-list -S apple-tool:,apple:,codesign: -s -k \${password} login.keychain > /dev/null
                """
            }
        }

        config.ios.profiles.each {
            steps.sh """${config.debugSh}
                cd "${steps.WORKSPACE}/${config.srcRootPath}"
                uuid=`grep UUID -A1 -a ${it} | grep -io "[-A-F0-9]\\{36\\}"`
                cp -f ${it} ~/Library/MobileDevice/Provisioning\\ Profiles/\${uuid}.mobileprovision
            """
        }
    }

    @Override
    def build() {
        String newFileName = fileArchiver.getFileName()

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
                steps.withCredentials([steps.usernamePassword(credentialsId: config.macos.loginCredentialId, passwordVariable: 'password', usernameVariable: 'username'),
                                       steps.file(credentialsId: config.xcode.authenticationKey.keyFileCredentialId, variable: 'authKeyPath')]) {
                    steps.sh """${config.debugSh}
                        security unlock-keychain -p \${password}
                        security set-keychain-settings -lut 21600 login.keychain
    
                        cd "${steps.WORKSPACE}/${config.srcRootPath}"
    
                        mkdir -p ido-cluster/outputs/files
                        rm -f ido-cluster/outputs/files/*
            
                        xcodebuild archive \
                          ${cmdBuildFile} \
                          -scheme ${it.scheme} \
                          -configuration ${it.configuration} \
                          -archivePath ./build/${it.name}/${config.productName}.xcarchive \
                          -destination 'generic/platform=iOS' \
                          ${cmdXcconfig} \
                          ${cmdAuth} -quiet
                    """
                }
                buildTypes.put(buildType, it.name)
            }

            // Export ipa
            steps.withCredentials([steps.usernamePassword(credentialsId: config.macos.loginCredentialId, passwordVariable: 'password', usernameVariable: 'username'),
                                   steps.file(credentialsId: config.xcode.authenticationKey.keyFileCredentialId, variable: 'authKeyPath')]) {
                steps.sh """${config.debugSh}
                     security unlock-keychain -p \${password}
                     security set-keychain-settings -lut 21600 login.keychain
    
                     cd "${steps.WORKSPACE}/${config.srcRootPath}"
            
                     xcodebuild -exportArchive \
                       -archivePath ./build/${buildTypes.get(buildType)}/${config.productName}.xcarchive \
                       -exportOptionsPlist ${it.exportOptionsPlist} \
                       -exportPath ./build/${buildTypes.get(buildType)} \
                       ${cmdXcconfig} \
                       ${cmdAuth} -quiet
                       
                     mv -f ./build/${buildTypes.get(buildType)}/*.ipa ido-cluster/outputs/files/${newFileName}-${it.name}.ipa
                     echo "${it.name} ipa exported."
    
                     xcodebuild ${cmdBuildFile} ${cmdXcconfig} -scheme ${it.scheme} -showBuildSettings \
                       | awk -F ' = ' '/ PRODUCT_BUNDLE_IDENTIFIER/ { print \$2 }' > ido-cluster/_PRODUCT_BUNDLE_IDENTIFIER
                     
                     plutil -p ./build/${buildTypes.get(buildType)}/${config.productName}.xcarchive/Info.plist \
                       | awk -F '"' '/CFBundleShortVersionString/ { print \$4 }' > ido-cluster/_BUNDLE_VERSION
                """
            }
        }
    }

    @Override
    def archive() {
        String newFileName = fileArchiver.getFileName()
        String uploadUrl = "${config.fileServer.uploadUrl}${config.fileServer.uploadRootPath}${config.productName}/" +
                config.branch + "/ios"

            String text = steps.libraryResource('tools/generate_qrcode.py')
            steps.writeFile(file: "generate_qrcode.py", text: text, encoding: "UTF-8")

            steps.sh """${config.debugSh}
                cd "${config.srcRootPath}/ido-cluster/outputs"
                touch ${newFileName}.html
                echo "<html>" >> ${newFileName}.html
                echo "<meta http-equiv='Content-Type' content='text/html; charset=utf-8' />" >> ${newFileName}.html
            """

        text = steps.libraryResource('builder/ios-ota-manifest.plist.template')
        steps.writeFile(file: "${config.srcRootPath}/ido-cluster/manifest.plist.template", text: text, encoding: "UTF-8")

        config.ios.buildOptions.each {
            String downloadUrl = "${config.fileServer.downloadUrl}${config.fileServer.uploadRootPath}${config.productName}/" +
                    config.branch + "/ios/files/${newFileName}-${it.name}.ipa"
            String plistUrl = "itms-services://?action=download-manifest&amp;" +
                    "url=${config.fileServer.downloadUrl}/${config.fileServer.uploadRootPath}${config.productName}/" +
                    config.branch + "/ios/files/${newFileName}-manifest-${it.name}.plist"

                steps.sh """${config.debugSh}
                    cd "${config.srcRootPath}"
                    software_package=${downloadUrl}
                    display_image=${config.ios.downloadDisplayImages.standard}
                    full_size_image=${config.ios.downloadDisplayImages.fullSize}
                    PRODUCT_BUNDLE_IDENTIFIER=\$(cat ido-cluster/_PRODUCT_BUNDLE_IDENTIFIER)
                    BUNDLE_VERSION=\$(cat ido-cluster/_BUNDLE_VERSION)
                    PRODUCT_TITLE=${config.productName}
                    
                    cp -f ido-cluster/manifest.plist.template ido-cluster/outputs/files/${newFileName}-manifest-${it.name}.plist
                    perl -p -i \
                        -e \"s|<software_package>|\${software_package}|g;\" \
                        -e \"s|<display_image>|\${display_image}|g;\" \
                        -e \"s|<full_size_image>|\${full_size_image}|g;\" \
                        -e \"s|<PRODUCT_BUNDLE_IDENTIFIER>|\${PRODUCT_BUNDLE_IDENTIFIER}|g;\" \
                        -e \"s|<BUNDLE_VERSION>|\${BUNDLE_VERSION}|g;\" \
                        -e \"s|<PRODUCT_TITLE>|\${PRODUCT_TITLE}|g\" \
                        ido-cluster/outputs/files/${newFileName}-manifest-${it.name}.plist

                    plistQrcode=\$(python3 ${steps.WORKSPACE}/generate_qrcode.py "${plistUrl}")
                    
                    cd "${config.srcRootPath}/ido-cluster/outputs"
                    echo "<hr><h3>${it.name}</h3>" >> ${newFileName}.html
                    echo "<p><a href='files/${newFileName}-${it.name}.ipa'>${newFileName}-${it.name}.ipa</a>" >> ${newFileName}.html
                    echo "<p><img src='data:image/png;base64,\${plistQrcode}'/>" >> ${newFileName}.html
                """
        }

        fileArchiver.upload(uploadUrl, "${config.srcRootPath}/ido-cluster/outputs")
    }
}
