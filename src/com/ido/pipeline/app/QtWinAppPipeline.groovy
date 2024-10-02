package com.ido.pipeline.app

import com.ido.pipeline.archiver.FileArchiver
import com.ido.pipeline.builderBase.WinPipeline

/**
 * @author xinnj
 */
class QtWinAppPipeline extends WinPipeline {
    FileArchiver fileArchiver

    QtWinAppPipeline(Object steps) {
        super(steps)
        this.useK8sAgent = false
        this.nodeName = "win && qt"
    }

    @Override
    prepare() {
        super.prepare()

        fileArchiver = new FileArchiver(steps, config)

        if (!config.qt.QT5_DIR && !config.qt.QT6_DIR) {
            steps.error "Both QT5_DIR and QT6_DIR are empty!"
        }
        if (!config.qt.qmakeParameters) {
            steps.error "qt.qmakeParameters is empty!"
        }
        if (!config.qt.msiConfig) {
            steps.error "qt.msiConfig is empty!"
        }
        if (!config.vs.vcvarsallFile) {
            steps.error "vs.vcvarsallFile is empty!"
        }
        if (!config.vs.arch) {
            steps.error "vs.arch is empty!"
        }

        if (config.qt.QT5_DIR) {
            steps.env.QT5_DIR = config.qt.QT5_DIR
        }
        if (config.qt.QT6_DIR) {
            steps.env.QT6_DIR = config.qt.QT6_DIR
        }

        steps.env.vs_vcvarsallFile = config.vs.vcvarsallFile
        steps.env.vs_arch = config.vs.arch
    }

    @Override
    def ut() {
        return null
    }

    @Override
    def codeAnalysis() {
        return null
    }

    @Override
    build() {
        steps.pwsh """${config.debugPwsh}
            Invoke-CmdScript -script "\$Env:vs_vcvarsallFile" -parameters "\$Env:vs_arch"
            
            cd "${steps.WORKSPACE}/${config.srcRootPath}"
            qmake.exe ${config.qt.qmakeParameters}
            jom.exe
        """
    }

    @Override
    def archive() {
        String newFileName = fileArchiver.getFileName()

        steps.pwsh """${config.debugPwsh}
            cd "${steps.WORKSPACE}/${config.srcRootPath}"
            
            envsubst -i "${config.qt.msiConfig}" -o "${config.qt.msiConfig}.final" -no-unset -no-empty
            if (-not\$?)
            {
                throw 'envsubst failure'
            }
            
            New-Item -ItemType Directory -Force -Path "ido-cluster/outputs"
            wixc.ps1 -config "${config.qt.msiConfig}.final" -output "ido-cluster/outputs/${newFileName}.msi"
        """

        String uploadUrl = "${config.fileServer.uploadUrl}${config.fileServer.uploadRootPath}${config.productName}/" +
                config.branch + "/win"

        fileArchiver.upload(uploadUrl, "${config.srcRootPath}/ido-cluster/outputs")
    }
}
