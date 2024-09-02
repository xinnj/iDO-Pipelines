package com.ido.pipeline.sdk

import com.ido.pipeline.Utils
import com.ido.pipeline.archiver.FileArchiver
import com.ido.pipeline.builderBase.WinPipeline

/**
 * @author xinnj
 */
class WinSdkPipeline extends WinPipeline{
    FileArchiver fileArchiver

    WinSdkPipeline(Object steps) {
        super(steps)
    }

    @Override
    def prepare() {
        fileArchiver = new FileArchiver(steps, config)

        String cmd = """
[Environment]::SetEnvironmentVariable('CI_BUILD_DEBUG','$config.config.sdk.win.buildDebug', 'User')
"""
        Utils.execRemoteWin(steps, config, cmd)
    }

    @Override
    def ut() {}

    @Override
    def codeAnalysis() {}

    @Override
    def build() {
        super.build(config)
    }

    @Override
    def archive() {
        String uploadUrl = "${config.fileServer.uploadUrl}${config.fileServer.uploadRootPath}/" +
                "${config._system.sdk.rootPath}/${config.productName}/${config.branch}/win"

        if (config.sdk.win.buildDebug) {
            genSdkInfo("Debug")
        }
        
        genSdkInfo("Release")

        fileArchiver.upload(uploadUrl, "${config.srcRootPath}/ido-cluster/outputs")
    }

    private genSdkInfo(String type) {
        String sdkFileName = "${config.productName}-${config.version}"
        steps.container('builder') {
            steps.sh """/bin/bash
                ${config.debugSh}
                cd "${config.srcRootPath}/ido-cluster/outputs"

                commitMessage=\$(git log --format=%B -n 1)
                commitAuthor=\$(git log --format=%an -n 1)
                
                touch ${config.productName}-${type}-latest.json
                echo "{" >> ${config.productName}-${type}-latest.json
                echo "    \"name\": \"${config.productName}\"" >> ${config.productName}-${type}-latest.json
                echo "    \"version\": \"${config.version}\"" >> ${config.productName}-${type}-latest.json
                echo "    \"message\": \"\${commitMessage}\"" >> ${config.productName}-${type}-latest.json
                echo "    \"author\": \"\${commitAuthor}\"" >> ${config.productName}-${type}-latest.json
                
                IN=\$(md5sum ${sdkFileName}-${type}.zip)
                arrIN=(\$IN)
                md5=\${arrIN[0]}
                echo "    \"md5\": \"\${md5}\"" >> ${config.productName}-${type}-latest.json
                
                echo "}" >> ${config.productName}-${type}-latest.json
            """
        }
    }
}
