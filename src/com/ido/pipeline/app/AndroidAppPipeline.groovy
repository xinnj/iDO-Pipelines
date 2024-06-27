package com.ido.pipeline.app

import com.ido.pipeline.Utils
import com.ido.pipeline.archiver.FileArchiver
import com.ido.pipeline.languageBase.JdkPipeline

/**
 * @author xinnj
 */
class AndroidAppPipeline extends JdkPipeline {
    FileArchiver fileArchiver

    AndroidAppPipeline(Object steps) {
        super(steps)
    }

    @Override
    Map runPipeline(Map config) {
        String builder = steps.libraryResource(resource: 'pod-template/android-builder.yaml', encoding: 'UTF-8')
        builder = builder.replaceAll('<builderImage>', config.java.builderImage)
                .replaceAll('<androidCmdLineToolsUrl>', config._system.android.cmdLineToolsUrl)
        config.podTemplate = builder

        return super.runPipeline(config)
    }

    @Override
    def prepare() {
        super.prepare()

        fileArchiver = new FileArchiver(steps, config)

        if (!config.android.sdkPackagesRequired) {
            steps.error "sdkPackagesRequired is empty!"
        }

        config.java.buildTool = "gradle"
        config.put("context", config.android)
        config.parallelBuildArchive = false

        String proxy = ""
        if (config._system.android.proxy.enable) {
            proxy = "--no_https --proxy=http --proxy_host=${config._system.android.proxy.host} --proxy_port=${config._system.android.proxy.port}"
        }
        steps.container('builder') {
            List<String> sdkPackagesInstalled = steps.sh(returnStdout: true, encoding: "UTF-8",
                    script: """${config.debugSh}
\${ANDROID_HOME}/cmdline-tools/latest/bin/sdkmanager --list_installed | tail -n +4 | awk '{print \$1}'
""")
                    .trim()
                    .split(System.lineSeparator()) as List<String>

            List<String> sdkPackagesToBeInstalled = (config.android.sdkPackagesRequired as List<String>)
                    .findAll {!sdkPackagesInstalled.contains(it)}

            String sdkPackages = ""
            sdkPackagesToBeInstalled.each {
                sdkPackages = sdkPackages + "\"$it\" "
            }

            steps.sh """${config.debugSh}
                if [ ! -e \${ANDROID_HOME}/licenses-agreed ]; then
                    yes | \${ANDROID_HOME}/cmdline-tools/latest/bin/sdkmanager ${proxy} --licenses
                    touch \${ANDROID_HOME}/licenses-agreed
                fi
                if [ -n "${sdkPackages}" ]; then
                    yes | \${ANDROID_HOME}/cmdline-tools/latest/bin/sdkmanager ${proxy} --install $sdkPackages
                fi
            """
        }
    }

    @Override
    def build() {
        String newFileName = fileArchiver.getFileName()
        steps.container('builder') {
            String updateDependenciesArgs = ""
            if (config.java.forceUpdateDependencies) {
                updateDependenciesArgs = "--refresh-dependencies"
            }

            steps.sh """${config.debugSh}
                cd "${config.srcRootPath}"
                mkdir -p ido-cluster/outputs/files
                rm -f ido-cluster/outputs/files/*

                if [ "$config.android.buildDebug" = "true" ]; then
                    sh ./gradlew assembleDebug \
                        --no-daemon \
                        -x test \
                        ${updateDependenciesArgs} \
                        -I "${steps.env.WORKSPACE}/${config.srcRootPath}/default-gradle-init.gradle" \
                        -Dfile.encoding=UTF-8 \
                        "-Dorg.gradle.jvmargs=-Xmx2048m -XX:+HeapDumpOnOutOfMemoryError -Dfile.encoding=UTF-8" \
                        -p ${config.java.moduleName}
                    numFound=\$(find ./ -type f -name "*-debug*.apk" -not -path "./outputs/*" -print | wc -l)
                    if [ \$numFound -lt 1 ]; then
                        echo "Can't find debug version apk file!"
                        exit 1
                    fi
                    if [ \$numFound -gt 1 ]; then
                        echo "Find multiple debug version apk files!"
                        exit 1
                    fi
                    find ./ -type f -name "*-debug*.apk" -not -path "./outputs/*" \
                        -exec mv "{}" "ido-cluster/outputs/files/${newFileName}-debug.apk" \\;
                fi
                if [ "$config.android.buildRelease" = "true" ]; then
                    sh ./gradlew assembleRelease \
                        --no-daemon \
                        -x test \
                        ${updateDependenciesArgs} \
                        -I "${steps.env.WORKSPACE}/${config.srcRootPath}/default-gradle-init.gradle" \
                        -Dfile.encoding=UTF-8 \
                        "-Dorg.gradle.jvmargs=-Xmx2048m -XX:+HeapDumpOnOutOfMemoryError -Dfile.encoding=UTF-8" \
                        -p ${config.java.moduleName}
                    numFound=\$(find ./ -type f -name "*-release*.apk" -not -path "./outputs/*" -print | wc -l)
                    if [ \$numFound -lt 1 ]; then
                        echo "Can't find release version apk file!"
                        exit 1
                    fi
                    if [ \$numFound -gt 1 ]; then
                        echo "Find multiple release version apk files!"
                        exit 1
                    fi
                    find ./ -type f -name "*-release*.apk" -not -path "./outputs/*" \
                        -exec mv "{}" "ido-cluster/outputs/files/${newFileName}-release.apk" \\;
                fi
            """
        }
    }

    @Override
    def archive() {
        String newFileName = fileArchiver.getFileName()
        steps.container('uploader') {
            String uploadUrl = "${config.fileServer.uploadUrl}${config.fileServer.uploadRootPath}${config.productName}/" +
                    config.branch + "/android"
            String debugDownloadUrl = "${config.fileServer.downloadUrl}${config.fileServer.uploadRootPath}/${config.productName}/" +
                    config.branch + "/android/files/${newFileName}-debug.apk"
            String releaseDownloadUrl = "${config.fileServer.downloadUrl}${config.fileServer.uploadRootPath}/${config.productName}/" +
                    config.branch + "/android/files/${newFileName}-release.apk"


            steps.sh """${config.debugSh}
                cd "${config.srcRootPath}/ido-cluster/outputs"
                touch ${newFileName}.html
                echo "<html>" >> ${newFileName}.html
                echo "<meta http-equiv='Content-Type' content='text/html; charset=utf-8' />" >> ${newFileName}.html
                
                if [ "${config.android.buildDebug}" = "true" ]; then
                    echo "debugDownloadUrl: ${debugDownloadUrl}"
                    qrencode --output qrcode.png "${debugDownloadUrl}"
                    if [ ! -f qrcode.png ]; then
                        echo QR code is not generated!
                        exit 1
                    fi
                    debugQrcode="\$(cat qrcode.png | base64 )"
                    rm -f qrcode.png
                    
                    echo "<hr><p><a href='files/${newFileName}-debug.apk'>${newFileName}-debug.apk</a>" >> ${newFileName}.html
                    echo "<p><img src='data:image/png;base64,\${debugQrcode}'/>" >> ${newFileName}.html
                fi
                
                if [ "${config.android.buildRelease}" = "true" ]; then
                    echo "releaseDownloadUrl: ${releaseDownloadUrl}"
                    qrencode --output qrcode.png "${releaseDownloadUrl}"
                    if [ ! -f qrcode.png ]; then
                        echo QR code is not generated!
                        exit 1
                    fi
                    releaseQrcode="\$(cat qrcode.png | base64 )"
                    rm -f qrcode.png
                    
                    echo "<hr><p><a href='files/${newFileName}-release.apk'>${newFileName}-release.apk</a>" >> ${newFileName}.html
                    echo "<p><img src='data:image/png;base64,\${releaseQrcode}'/>" >> ${newFileName}.html
                fi
            """

            fileArchiver.upload(uploadUrl, "${config.srcRootPath}/ido-cluster/outputs")
        }
    }
}
