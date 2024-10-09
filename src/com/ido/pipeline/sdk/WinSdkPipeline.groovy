package com.ido.pipeline.sdk

import com.ido.pipeline.archiver.FileArchiver
import com.ido.pipeline.builderBase.WinPipeline
import com.ido.pipeline.Utils

/**
 * @author xinnj
 */
class WinSdkPipeline extends WinPipeline {
    String category = "win"
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
                "${config._system.sdk.rootPath}/${config.productName}/${config.branch}/${category}"

        def sdkDownloader = new SdkDownloader(steps, config)

        if (config.sdk.win.buildDebug) {
            sdkDownloader.genSdkLatestInfo(category, "debug")
        }

        sdkDownloader.genSdkLatestInfo(category, "release")

        fileArchiver.upload(uploadUrl, "${config.srcRootPath}/ido-cluster/outputs")
    }
}
