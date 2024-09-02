package com.ido.pipeline.builderBase

import com.ido.pipeline.Utils
import com.ido.pipeline.base.BuildPipeline

/**
 * @author xinnj
 */
abstract class WinPipeline extends BuildPipeline{
    String smbServerAddress = ""

    WinPipeline(Object steps) {
        super(steps)
    }

    @Override
    Map runPipeline(Map config) {
        steps.withCredentials([steps.usernamePassword(credentialsId: config.win.loginCredentialId, passwordVariable: 'password', usernameVariable: 'username')]) {
            if (config.win.useRemoteBuilder) {
                steps.lock(label: config.win.remoteBuilderTags, quantity: 1, resourceSelectStrategy: "random", variable: "builder") {
                    String remoteHost = steps.env.builder0_host
                    String remotePort = steps.env.builder0_port
                    steps.echo "Locked remote builder: " + steps.env.builder

                    config.podTemplate = steps.libraryResource(resource: "pod-template/win-remote-builder.yaml", encoding: 'UTF-8')
                            .replaceAll('<builderImage>', config._system.win.remoteBuilderImage)
                            .replaceAll('<REMOTE_HOST>', remoteHost)
                            .replaceAll('<REMOTE_PORT>', remotePort)
                }
            } else {

                config.podTemplate = steps.libraryResource(resource: "pod-template/win-builder.yaml", encoding: 'UTF-8')
                        .replaceAll('<builderImage>', config._system.win.builderImage)
                        .replaceAll('<winImage>', config.win.winImage)
            }

            String username = steps.env.username
            String password = steps.env.password
            config.podTemplate = config.podTemplate
                    .replaceAll('<USERNAME>', username)
                    .replaceAll('<PASSWORD>', password)
        }

        return super.runPipeline(config)
    }

    @Override
    def prepare() {
        config.builderType = "win"
        super.prepare()

        config.vmWorkspace = (steps.WORKSPACE as String).replace("/home/jenkins/agent", "R:").replace("/", "\\")

        steps.container('builder') {
            String cmd
            if (config.win.useRemoteBuilder) {
                smbServerAddress = "\\\\${config._system.win.fakeIP}\\${config._system.smbServer.shareName} ${config._system.smbServer.password} /user:${config._system.smbServer.user}"

                String host = (config._system.smbServer.external as String).split(':')[0]
                String port = (config._system.smbServer.external as String).split(':')[1]

                cmd = """
if (!(Test-Path -Path "\$PROFILE" )) {
    New-Item -Type File -Path "\$PROFILE" -Force
}

if (!(Select-String -Path "\$PROFILE" -Pattern "ErrorActionPreference" -Quiet -SimpleMatch)) {
    Add-Content -Path "\$PROFILE" -Value "`\$ErrorActionPreference = 'SilentlyContinue'"
}
if (!(Select-String -Path "\$PROFILE" -Pattern "[System.Console]::OutputEncoding" -Quiet -SimpleMatch)) {
    Add-Content -Path "\$PROFILE" -Value "[System.Console]::OutputEncoding = [System.Console]::InputEncoding = [System.Text.Encoding]::UTF8"
}
if (!(Select-String -Path "\$PROFILE" -Pattern "net use R:" -Quiet -SimpleMatch)) {
    Add-Content -Path "\$PROFILE" -Value "if (-not(Test-Path -Path R:/workspace -PathType Container)) {net use R: ${smbServerAddress} | out-null}"
} else {
    (Get-Content \$PROFILE) -replace ".*net use R:.*", "if (-not(Test-Path -Path R:/workspace -PathType Container)) {net use R: ${smbServerAddress} | out-null}" | Set-Content \$PROFILE
}

if (!(Get-PackageProvider -ListAvailable | select-string "NuGet")) {
    Install-PackageProvider -Name NuGet -MinimumVersion 2.8.5.201 -Force
}

if (!(Get-Module -ListAvailable -Name LoopbackAdapter)) {
    Install-Module -Name LoopbackAdapter -MinimumVersion 1.2.0.0 -Force
}

if (!(Get-Module -ListAvailable -Name WintellectPowerShell)) {
    Install-Module -Name WintellectPowerShell -Force
}

if (!(Get-LoopbackAdapter -Name smb)) {
    \$interface = New-LoopbackAdapter -Name smb -Force
    \$interface | Disable-NetAdapterBinding -ComponentID ms_msclient,ms_pacer,ms_server,ms_lltdio,ms_rspndr
    \$interface | Set-DnsClient -RegisterThisConnectionsAddress ${config._system.win.fakeIP} -PassThru
    \$interface | Set-NetIPInterface -InterfaceMetric '254' -WeakHostSend Enabled -WeakHostReceive Enabled -Dhcp Disabled
    \$interface | New-NetIPAddress -IPAddress ${config._system.win.fakeIP} -PrefixLength 32 -AddressFamily IPv4
    shotdown /f
}
"""
                steps.sh """
                    { set +x; } 2>/dev/null
                    currentHome=\$HOME
                    sudo -- sh -c "cat \${currentHome}/hosts >> /etc/hosts"
                """
                Utils.execRemoteWin(steps, config, cmd)

                steps.sh """
                    for i in {1..30}; do
                        ssh -o ConnectTimeout=10 remote-host "pwd"
                        if [ "\$?" -eq "0" ]; then
                            echo "Remote host is up."
                            break
                        fi
                    done
                """
                cmd = """
\$ErrorActionPreference = "SilentlyContinue"
netsh interface portproxy delete v4tov4 listenport=445 | out-null
\$ErrorActionPreference = "Stop"
[System.Net.Dns]::GetHostAddresses("${host}") | foreach {\$remoteIP=\$_.IPAddressToString}
netsh interface portproxy add v4tov4 listenport=445 connectaddress=\$remoteIP connectport=${port}
"""
                Utils.execRemoteWin(steps, config, cmd)
            } else {
                smbServerAddress = "\\\\${config._system.smbServer.internal}\\${config._system.smbServer.shareName} ${config._system.smbServer.password} /user:${config._system.smbServer.user}"
                steps.sh """
                    { set +x; } 2>/dev/null
                    if [[ \$(grep -E -c '(svm|vmx)' /proc/cpuinfo) -le 0 ]]; then
                        echo KVM not possible on this host
                        exit 1
                    fi
                    
                    sudo -- sh -c "echo '127.0.0.1 remote-host' >> /etc/hosts"
                """

                cmd = """
if (!(Test-Path -Path "\$PROFILE" )) {
    New-Item -Type File -Path "\$PROFILE" -Force
}

if (!(Select-String -Path "\$PROFILE" -Pattern "ErrorActionPreference" -Quiet -SimpleMatch)) {
    Add-Content -Path "\$PROFILE" -Value "`\$ErrorActionPreference = 'SilentlyContinue'"
}
if (!(Select-String -Path "\$PROFILE" -Pattern "[System.Console]::OutputEncoding" -Quiet -SimpleMatch)) {
    Add-Content -Path "\$PROFILE" -Value "[System.Console]::OutputEncoding = [System.Console]::InputEncoding = [System.Text.Encoding]::UTF8"
}
if (!(Select-String -Path "\$PROFILE" -Pattern "net use R:" -Quiet -SimpleMatch)) {
    Add-Content -Path "\$PROFILE" -Value "if (-not(Test-Path -Path R:/workspace -PathType Container)) {net use R: ${smbServerAddress} | out-null}"
} else {
    (Get-Content \$PROFILE) -replace ".*net use R:.*", "if (-not(Test-Path -Path R:/workspace -PathType Container)) {net use R: ${smbServerAddress} | out-null}" | Set-Content \$PROFILE
}

if (!(Get-PackageProvider -ListAvailable | select-string "NuGet")) {
    Install-PackageProvider -Name NuGet -MinimumVersion 2.8.5.201 -Force
}
"""
                Utils.execRemoteWin(steps, config, cmd)
            }

            cmd = """
powershell -command \"New-ItemProperty -Path 'HKLM:\\SOFTWARE\\OpenSSH' -Name DefaultShell -Value 'C:\\Windows\\System32\\WindowsPowerShell\\v1.0\\powershell.exe' -PropertyType String -Force; Set-ExecutionPolicy -ExecutionPolicy Bypass -Scope CurrentUser\"
powershell -command \"[Environment]::SetEnvironmentVariable('DOTNET_CLI_TELEMETRY_OPTOUT','true', 'User')\"
"""
            Utils.execRemoteWin(steps, config, cmd)
        }
    }
}
