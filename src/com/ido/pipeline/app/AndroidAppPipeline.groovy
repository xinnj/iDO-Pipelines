package com.ido.pipeline.app

import com.ido.pipeline.Utils
import com.ido.pipeline.archiver.FileArchiver
import com.ido.pipeline.languageBase.AndroidPipeline

/**
 * @author xinnj
 */
class AndroidAppPipeline extends AndroidPipeline {
    FileArchiver fileArchiver

    AndroidAppPipeline(Object steps) {
        super(steps)
    }

    @Override
    def prepare() {
        super.prepare()
        config.category = "android"
        fileArchiver = new FileArchiver(steps, config)
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

        String debugDownloadUrl, debugQrcode
        if (config.android.buildDebug) {
            debugDownloadUrl = "${config.fileServer.downloadUrl}${config.fileServer.uploadRootPath}${config.productName}/" +
                    config.branch + "/android/files/${newFileName}-debug.apk"
            debugQrcode = Utils.genQrcodeToString(debugDownloadUrl)
        }

        String releaseDownloadUrl, releaseQrcode
        if (config.android.buildRelease) {
            releaseDownloadUrl = "${config.fileServer.downloadUrl}${config.fileServer.uploadRootPath}${config.productName}/" +
                    config.branch + "/android/files/${newFileName}-release.apk"
            releaseQrcode = Utils.genQrcodeToString(releaseDownloadUrl)
        }

        execOnAgent('builder', {
            steps.sh """${config.debugSh}
                cd "${config.srcRootPath}/ido-cluster/outputs"
                touch ${newFileName}.html
                echo "<html>" >> ${newFileName}.html
                echo "<meta http-equiv='Content-Type' content='text/html; charset=utf-8' />" >> ${newFileName}.html
                
                if [ "${config.android.buildDebug}" = "true" ]; then
                    echo "debugDownloadUrl: ${debugDownloadUrl}"
                    echo "<hr><p><a href='files/${newFileName}-debug.apk'>${newFileName}-debug.apk</a>" >> ${newFileName}.html
                    echo "<p><img src='data:image/png;base64,${debugQrcode}'/>" >> ${newFileName}.html
                fi
                
                if [ "${config.android.buildRelease}" = "true" ]; then
                    echo "releaseDownloadUrl: ${releaseDownloadUrl}"
                    echo "<hr><p><a href='files/${newFileName}-release.apk'>${newFileName}-release.apk</a>" >> ${newFileName}.html
                    echo "<p><img src='data:image/png;base64,${releaseQrcode}'/>" >> ${newFileName}.html
                fi
            """
        })

        fileArchiver.upload(uploadUrl, "${config.srcRootPath}/ido-cluster/outputs")
    }
}
