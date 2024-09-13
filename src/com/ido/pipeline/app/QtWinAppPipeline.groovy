package com.ido.pipeline.app

import com.ido.pipeline.Utils
import com.ido.pipeline.archiver.FileArchiver
import com.ido.pipeline.builderBase.WinPipeline

/**
 * @author xinnj
 */
class QtWinAppPipeline extends WinPipeline{
    FileArchiver fileArchiver

    QtWinAppPipeline(Object steps) {
        super(steps)
    }

    @Override
    prepare(){
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

        String cmd = """
[Environment]::SetEnvironmentVariable('vs_vcvarsallFile','${config.vs.vcvarsallFile}', 'User')
[Environment]::SetEnvironmentVariable('vs_arch','${config.vs.arch}', 'User')
"""

        if (config.qt.QT5_DIR) {
            cmd = cmd + "[Environment]::SetEnvironmentVariable('QT5_DIR','${config.qt.QT5_DIR}', 'User')\n"
        }
        if (config.qt.QT6_DIR) {
            cmd = cmd + "[Environment]::SetEnvironmentVariable('QT6_DIR','${config.qt.QT6_DIR}', 'User')\n"
        }

        Utils.execRemoteWin(steps, config, cmd)
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
        String cmd = """
Invoke-CmdScript -script "\$Env:vs_vcvarsallFile" -parameters "\$Env:vs_arch"

cd "${config.vmWorkspace}/${config.srcRootPath}"
qmake.exe ${config.qt.qmakeParameters}
jom.exe
"""

        Utils.execRemoteWin(steps, config, cmd)
    }

    @Override
    def archive() {
        String newFileName = fileArchiver.getFileName()

        String cmd = """
cd "${config.vmWorkspace}/${config.srcRootPath}"

envsubst -i "${config.qt.msiConfig}" -o "${config.qt.msiConfig}.final" -no-unset -no-empty
if (-not\$?)
{
    throw 'envsubst failure'
}

New-Item -ItemType Directory -Force -Path "ido-cluster/outputs"
wixc.ps1 -config "${config.qt.msiConfig}.final" -output "ido-cluster/outputs/${newFileName}.msi"
"""

        Utils.execRemoteWin(steps, config, cmd)

        String uploadUrl = "${config.fileServer.uploadUrl}${config.fileServer.uploadRootPath}${config.productName}/" +
                config.branch + "/win"

        fileArchiver.upload(uploadUrl, "${config.srcRootPath}/ido-cluster/outputs")
    }
}
