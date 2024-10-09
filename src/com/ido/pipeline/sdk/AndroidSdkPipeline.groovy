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

        execOnAgent('builder', {
            steps.sh """${config.debugSh}
                cd "${config.srcRootPath}"

                if [ "$config.android.buildDebug" = "true" ]; then
                    sdkFilenamePrefix="${config.productName}-${category}-debug-${config.version}"
                    mkdir -p "ido-cluster/outputs/\${sdkFilenamePrefix}"
                    rm -f "ido-cluster/outputs/\${sdkFilenamePrefix}/*"
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
                        -exec mv "{}" "ido-cluster/outputs/\${sdkFilenamePrefix}/" \\;
                    cd "ido-cluster/outputs/"
                    zip -ry \${sdkFilenamePrefix}.zip \${sdkFilenamePrefix}/*
                    rm -r \${sdkFilenamePrefix}
                    cd "${config.srcRootPath}"
                fi
                if [ "$config.android.buildRelease" = "true" ]; then
                    sdkFilenamePrefix="${config.productName}-${category}-release-${config.version}"
                    mkdir -p "ido-cluster/outputs/\${sdkFilenamePrefix}"
                    rm -f "ido-cluster/outputs/\${sdkFilenamePrefix}/*"
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
                        -exec mv "{}" "ido-cluster/outputs/\${sdkFilenamePrefix}/" \\;
                    cd "ido-cluster/outputs/"
                    zip -ry \${sdkFilenamePrefix}.zip \${sdkFilenamePrefix}/*
                    rm -r \${sdkFilenamePrefix}
                    cd "${config.srcRootPath}"
                fi
            """
        })
    }

    @Override
    def archive() {
        String uploadUrl = "${config.fileServer.uploadUrl}${config.fileServer.uploadRootPath}/" +
                "${config._system.sdk.rootPath}/${config.productName}/${config.branch}/${category}"

        if (config.android.buildDebug) {
            Utils.genSdkLatestInfo(steps, config, category, "debug")
        }

        if (config.android.buildRelease) {
            Utils.genSdkLatestInfo(steps, config, category, "release")
        }

        fileArchiver.upload(uploadUrl, "${config.srcRootPath}/ido-cluster/outputs")
    }
}
