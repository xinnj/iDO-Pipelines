package com.ido.pipeline.sdk

import com.ido.pipeline.archiver.FileArchiver
import com.ido.pipeline.builderBase.WinPipeline
import com.ido.pipeline.Utils

/**
 * @author xinnj
 */
class WinSdkPipeline extends WinPipeline {
    FileArchiver fileArchiver

    WinSdkPipeline(Object steps) {
        super(steps)
        this.useK8sAgent = false
        this.nodeName = "win"
    }

    @Override
    def prepare() {
        fileArchiver = new FileArchiver(steps, config)
    }

    @Override
    def ut() {}

    @Override
    def codeAnalysis() {}

    @Override
    def build() {
        super.build()
    }

    @Override
    def archive() {
        String uploadUrl = "${config.fileServer.uploadUrl}${config.fileServer.uploadRootPath}/" +
                "${config._system.sdk.rootPath}/${config.productName}/${config.branch}/win"

        if (config.sdk.win.buildDebug) {
            Utils.genSdkInfo(steps, config, "Debug")
        }

        Utils.genSdkInfo(steps, config, "Release")

        fileArchiver.upload(uploadUrl, "${config.srcRootPath}/ido-cluster/outputs")
    }
}
