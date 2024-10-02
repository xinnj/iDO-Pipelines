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
        this.useK8sAgent = true
        this.nodeName = "android"
    }

    @Override
    Map runPipeline(Map config) {
        if (config.useK8sAgent == null || config.useK8sAgent) {
            String builder = steps.libraryResource(resource: 'pod-template/android-builder.yaml', encoding: 'UTF-8')
            builder = builder.replaceAll('<builderImage>', config.java.builderImage)
                    .replaceAll('<androidCmdLineToolsUrl>', config._system.android.cmdLineToolsUrl)
            config.podTemplate = builder
        }

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
        List<String> sdkPackagesInstalledOutput = execOnAgent('builder', {
            steps.sh(returnStdout: true, encoding: "UTF-8", script: """${config.debugSh}
                \${ANDROID_HOME}/cmdline-tools/latest/bin/sdkmanager --list_installed | tail -n +4 | awk '{print \$1}'
            """)
                    .trim()
                    .split(System.lineSeparator()) as List<String>
        })

        Boolean start = false
        def sdkPackagesInstalled = []
        for (int i = 0; i < sdkPackagesInstalledOutput.size(); i++) {
            if (start) {
                sdkPackagesInstalled.add(sdkPackagesInstalledOutput[i])
            }
            if (sdkPackagesInstalledOutput[i].matches("^\\s*----.*")) {
                start = true
            }
        }

        steps.echo "sdkPackagesInstalled: ${sdkPackagesInstalled}"

        List<String> sdkPackagesToBeInstalled = (config.android.sdkPackagesRequired as List<String>)
                .findAll { !sdkPackagesInstalled.contains(it) }

        String sdkPackages = ""
        sdkPackagesToBeInstalled.each {
            sdkPackages = sdkPackages + "'$it' "
        }
        steps.echo "sdkPackages: ${sdkPackages}"

        execOnAgent('builder', {
            steps.sh """${config.debugSh}
                if [ ! -e \${ANDROID_HOME}/licenses-agreed ]; then
                    yes | \${ANDROID_HOME}/cmdline-tools/latest/bin/sdkmanager ${proxy} --licenses
                    touch \${ANDROID_HOME}/licenses-agreed
                fi
                if [ -n "${sdkPackages}" ]; then
                    yes | \${ANDROID_HOME}/cmdline-tools/latest/bin/sdkmanager ${proxy} --install $sdkPackages
                fi
            """
        })
    }

    @Override
    def build() {
        String newFileName = fileArchiver.getFileName()
        String updateDependenciesArgs = ""
        if (config.java.forceUpdateDependencies) {
            updateDependenciesArgs = "--refresh-dependencies"
        }

        String initScriptArgs = ""
        if (config.java.defaultGradleInitScript) {
            initScriptArgs = "-I \"${steps.WORKSPACE}/${config.srcRootPath}/default-gradle-init.gradle\""
        }

        execOnAgent('builder', {
            steps.sh """${config.debugSh}
                cd "${config.srcRootPath}"
                mkdir -p ido-cluster/outputs/files
                rm -f ido-cluster/outputs/files/*

                if [ "$config.android.buildDebug" = "true" ]; then
                    sh ./gradlew assembleDebug \
                        --no-daemon \
                        -x test \
                        ${updateDependenciesArgs} \
                        ${initScriptArgs} \
                        -Dfile.encoding=UTF-8 \
                        "-Dorg.gradle.jvmargs=-Xmx2048m -XX:+HeapDumpOnOutOfMemoryError -Dfile.encoding=UTF-8" \
                        -p ${config.java.moduleName}
                    numFound=\$(find ./ -type f -name "*.apk" -not -path "./ido-cluster/*" -print | wc -l)
                    if [ \$numFound -lt 1 ]; then
                        echo "Can't find debug version apk file!"
                        exit 1
                    fi
                    if [ \$numFound -gt 1 ]; then
                        echo "Find multiple debug version apk files!"
                        exit 1
                    fi
                    find ./ -type f -name "*.apk" -not -path "./ido-cluster/*" \
                        -exec mv "{}" "ido-cluster/outputs/files/${newFileName}-debug.apk" \\;
                fi
                if [ "$config.android.buildRelease" = "true" ]; then
                    sh ./gradlew assembleRelease \
                        --no-daemon \
                        -x test \
                        ${updateDependenciesArgs} \
                        ${initScriptArgs} \
                        -Dfile.encoding=UTF-8 \
                        "-Dorg.gradle.jvmargs=-Xmx2048m -XX:+HeapDumpOnOutOfMemoryError -Dfile.encoding=UTF-8" \
                        -p ${config.java.moduleName}
                    numFound=\$(find ./ -type f -name "*.apk" -not -path "./ido-cluster/*" -print | wc -l)
                    if [ \$numFound -lt 1 ]; then
                        echo "Can't find release version apk file!"
                        exit 1
                    fi
                    if [ \$numFound -gt 1 ]; then
                        echo "Find multiple release version apk files!"
                        exit 1
                    fi
                    find ./ -type f -name "*.apk" -not -path "./ido-cluster/*" \
                        -exec mv "{}" "ido-cluster/outputs/files/${newFileName}-release.apk" \\;
                fi
            """
        })
    }

    @Override
    def archive() {
        String newFileName = fileArchiver.getFileName()
        String uploadUrl = "${config.fileServer.uploadUrl}${config.fileServer.uploadRootPath}${config.productName}/" +
                config.branch + "/android"

        if (!config.useK8sAgent) {
            String text = steps.libraryResource('tools/generate_qrcode.py')
            steps.writeFile(file: "generate_qrcode.py", text: text, encoding: "UTF-8")
        }

        String debugDownloadUrl = "${config.fileServer.downloadUrl}${config.fileServer.uploadRootPath}/${config.productName}/" +
                config.branch + "/android/files/${newFileName}-debug.apk"
        String releaseDownloadUrl = "${config.fileServer.downloadUrl}${config.fileServer.uploadRootPath}/${config.productName}/" +
                config.branch + "/android/files/${newFileName}-release.apk"

        execOnAgent('uploader', {
            steps.sh """${config.debugSh}
                cd "${config.srcRootPath}/ido-cluster/outputs"
                touch ${newFileName}.html
                echo "<html>" >> ${newFileName}.html
                echo "<meta http-equiv='Content-Type' content='text/html; charset=utf-8' />" >> ${newFileName}.html
                
                if [ "${config.android.buildDebug}" = "true" ]; then
                    echo "debugDownloadUrl: ${debugDownloadUrl}"

                    if [ "${config.useK8sAgent}" = "true" ]; then
                        qrencode --output qrcode.png "${debugDownloadUrl}"
                        if [ ! -f qrcode.png ]; then
                            echo QR code is not generated!
                            exit 1
                        fi
                        debugQrcode="\$(cat qrcode.png | base64 )"
                        rm -f qrcode.png
                    else
                        debugQrcode=\$(python3 ${steps.WORKSPACE}/generate_qrcode.py "${debugDownloadUrl}")
                    fi

                    echo "<hr><p><a href='files/${newFileName}-debug.apk'>${newFileName}-debug.apk</a>" >> ${newFileName}.html
                    echo "<p><img src='data:image/png;base64,\${debugQrcode}'/>" >> ${newFileName}.html
                fi
                
                if [ "${config.android.buildRelease}" = "true" ]; then
                    echo "releaseDownloadUrl: ${releaseDownloadUrl}"

                    if [ "${config.useK8sAgent}" = "true" ]; then
                        qrencode --output qrcode.png "${releaseDownloadUrl}"
                        if [ ! -f qrcode.png ]; then
                            echo QR code is not generated!
                            exit 1
                        fi
                        releaseQrcode="\$(cat qrcode.png | base64 )"
                        rm -f qrcode.png
                    else
                        releaseQrcode=\$(python3 ${steps.WORKSPACE}/generate_qrcode.py "${releaseDownloadUrl}")
                    fi
                    
                    echo "<hr><p><a href='files/${newFileName}-release.apk'>${newFileName}-release.apk</a>" >> ${newFileName}.html
                    echo "<p><img src='data:image/png;base64,\${releaseQrcode}'/>" >> ${newFileName}.html
                fi
            """
        })

        fileArchiver.upload(uploadUrl, "${config.srcRootPath}/ido-cluster/outputs")
    }
}
