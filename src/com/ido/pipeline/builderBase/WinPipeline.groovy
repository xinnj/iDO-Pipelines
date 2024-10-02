package com.ido.pipeline.builderBase

import com.ido.pipeline.base.BuildPipeline

/**
 * @author xinnj
 */
abstract class WinPipeline extends BuildPipeline {
    WinPipeline(Object steps) {
        super(steps)
    }

    @Override
    def prepare() {
        super.prepare()

        steps.pwsh """${config.debugPwsh}
            if (!(Test-Path -Path "\$PROFILE" )) {
                New-Item -Type File -Path "\$PROFILE" -Force
            }

            if (!(Select-String -Path "\$PROFILE" -Pattern "PSNativeCommandUseErrorActionPreference " -Quiet -SimpleMatch)) {
                Add-Content -Path "\$PROFILE" -Value "`\$PSNativeCommandUseErrorActionPreference  = `\$true"
            }
            if (!(Select-String -Path "\$PROFILE" -Pattern "ErrorActionPreference" -Quiet -SimpleMatch)) {
                Add-Content -Path "\$PROFILE" -Value "`\$ErrorActionPreference = 'Stop'"
            }
            if (!(Select-String -Path "\$PROFILE" -Pattern "ProgressPreference" -Quiet -SimpleMatch)) {
                Add-Content -Path "\$PROFILE" -Value "`\$ProgressPreference = 'SilentlyContinue'"
            }
            if (!(Select-String -Path "\$PROFILE" -Pattern "[System.Console]::OutputEncoding" -Quiet -SimpleMatch)) {
                Add-Content -Path "\$PROFILE" -Value "[System.Console]::OutputEncoding = [System.Console]::InputEncoding = [System.Text.Encoding]::UTF8"
            }

            if (!(Get-PackageProvider -ListAvailable -name NuGet)) {
                Get-PackageProvider | where name -eq 'nuget' | Install-PackageProvider -Force
            }
            if (!(Get-Module -ListAvailable -Name WintellectPowerShell)) {
                Install-Module -Name WintellectPowerShell -Force -AllowClobber
            }
        """
    }
}
