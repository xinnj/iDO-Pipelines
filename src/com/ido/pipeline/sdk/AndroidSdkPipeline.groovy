package com.ido.pipeline.sdk

import com.ido.pipeline.Utils
import com.ido.pipeline.archiver.FileArchiver
import com.ido.pipeline.languageBase.AndroidPipeline

/**
 * @author xinnj
 */
class AndroidSdkPipeline extends AndroidPipeline {
    String category = "android"
    FileArchiver fileArchiver

    AndroidSdkPipeline(Object steps) {
        super(steps)
    }

    @Override
    def prepare() {
        super.prepare()
        fileArchiver = new FileArchiver(steps, config)
    }

    @Override
    def build() {
        String updateDependenciesArgs = ""
        if (config.java.forceUpdateDependencies) {
            updateDependenciesArgs = "--refresh-dependencies"
        }

        String initScriptArgs = ""
        if (config.java.defaultGradleInitScript) {
            initScriptArgs = "-I \"${steps.WORKSPACE}/${config.srcRootPath}/default-gradle-init.gradle\""
        }

        if (config.android.buildDebug) {
            String sdkFilenamePrefix = "${config.productName}-${category}-debug-${config.version}"
            execOnAgent('builder', {
                steps.sh """${config.debugSh}
                    cd "${config.srcRootPath}"
                    mkdir -p "ido-cluster/outputs/debug"
                    rm -f "ido-cluster/outputs/debug/*"
                    sh ./gradlew assembleDebug \
                        --no-daemon \
                        -x test \
                        ${updateDependenciesArgs} \
                        ${initScriptArgs} \
                        -Dfile.encoding=UTF-8 \
                        "-Dorg.gradle.jvmargs=-Xmx2048m -XX:+HeapDumpOnOutOfMemoryError -Dfile.encoding=UTF-8" \
                        -p ${config.java.moduleName}
                    numFound=\$(find ./ -type f -name "*.aar" -not -path "./ido-cluster/*" -print | wc -l)
                    if [ \$numFound -lt 1 ]; then
                        echo "Can't find debug version aar file!"
                        exit 1
                    fi
                    if [ \$numFound -gt 1 ]; then
                        echo "Find multiple debug version aar files!"
                        exit 1
                    fi
                    find ./ -type f -name "*.aar" -not -path "./ido-cluster/*" \
                        -exec mv "{}" "ido-cluster/outputs/debug/" \\;
                """
            })

            steps.zip(zipFile: "${config.srcRootPath}/ido-cluster/outputs/${sdkFilenamePrefix}.zip", dir: "${config.srcRootPath}/ido-cluster/outputs/debug/")
        }

        if (config.android.buildRelease) {
            String sdkFilenamePrefix = "${config.productName}-${category}-release-${config.version}"
            execOnAgent('builder', {
                steps.sh """${config.debugSh}
                    cd "${config.srcRootPath}"
                    mkdir -p "ido-cluster/outputs/release"
                    rm -f "ido-cluster/outputs/release/*"
                    sh ./gradlew assembleRelease \
                        --no-daemon \
                        -x test \
                        ${updateDependenciesArgs} \
                        ${initScriptArgs} \
                        -Dfile.encoding=UTF-8 \
                        "-Dorg.gradle.jvmargs=-Xmx2048m -XX:+HeapDumpOnOutOfMemoryError -Dfile.encoding=UTF-8" \
                        -p ${config.java.moduleName}
                    numFound=\$(find ./ -type f -name "*.aar" -not -path "./ido-cluster/*" -print | wc -l)
                    if [ \$numFound -lt 1 ]; then
                        echo "Can't find release version aar file!"
                        exit 1
                    fi
                    if [ \$numFound -gt 1 ]; then
                        echo "Find multiple release version aar files!"
                        exit 1
                    fi
                    find ./ -type f -name "*.aar" -not -path "./ido-cluster/*" \
                        -exec mv "{}" "ido-cluster/outputs/release/" \\;
                """
            })

            steps.zip(zipFile: "${config.srcRootPath}/ido-cluster/outputs/${sdkFilenamePrefix}.zip", dir: "${config.srcRootPath}/ido-cluster/outputs/release/")
        }

        execOnAgent('builder', {
            steps.sh """${config.debugSh}
                    cd "${config.srcRootPath}"
                    rm -rf "ido-cluster/outputs/debug"
                    rm -rf "ido-cluster/outputs/release"
            """
        })
    }

    @Override
    def archive() {
        String uploadUrl = "${config.fileServer.uploadUrl}${config.fileServer.uploadRootPath}/" +
                "${config._system.sdk.rootPath}/${config.productName}/${config.branch}/${category}"

        def sdkDownloader = new SdkDownloader(steps, config)

        if (config.android.buildDebug) {
            sdkDownloader.genSdkLatestInfo(category, "debug")
        }

        if (config.android.buildRelease) {
            sdkDownloader.genSdkLatestInfo(category, "release")
        }

        fileArchiver.upload(uploadUrl, "${config.srcRootPath}/ido-cluster/outputs")
    }
}
