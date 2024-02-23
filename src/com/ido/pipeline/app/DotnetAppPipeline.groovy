package com.ido.pipeline.app

import com.ido.pipeline.Utils
import com.ido.pipeline.archiver.FileArchiver
import com.ido.pipeline.base.BasePipeline

/**
 * @author xinnj
 */
class DotnetAppPipeline extends BasePipeline {
    FileArchiver fileArchiver
    String vmWorkspace = ""
    String smbServerAddress = ""

    DotnetAppPipeline(Object steps) {
        super(steps)
    }

    @Override
    Map runPipeline(Map config) {
        steps.withCredentials([steps.usernamePassword(credentialsId: config.dotnet.loginCredentialId, passwordVariable: 'password', usernameVariable: 'username')]) {
            if (config.dotnet.useRemoteBuilder) {
                steps.lock(label: "win-builder", quantity: 1, resourceSelectStrategy: "random", variable: "builder") {
                    String remoteHost = steps.env.builder0_host
                    String remotePort = steps.env.builder0_port
                    String username = steps.env.username
                    String password = steps.env.password
                    steps.echo "Locked remote builder: " + steps.env.builder

                    config.podTemplate = steps.libraryResource(resource: "pod-template/win-remote-builder.yaml", encoding: 'UTF-8')
                            .replaceAll('<builderImage>', config._system.win.remoteBuilderImage)
                            .replaceAll('<REMOTE_HOST>', remoteHost)
                            .replaceAll('<REMOTE_PORT>', remotePort)
                            .replaceAll('<USERNAME>', username)
                            .replaceAll('<PASSWORD>', password)
                }
            } else {
                String username = steps.env.username
                String password = steps.env.password
                config.podTemplate = steps.libraryResource(resource: "pod-template/win-builder.yaml", encoding: 'UTF-8')
                        .replaceAll('<builderImage>', config._system.win.builderImage)
                        .replaceAll('<winImage>', config.dotnet.winImage)
                        .replaceAll('<USERNAME>', username)
                        .replaceAll('<PASSWORD>', password)
            }
        }

        return super.runPipeline(config)
    }

    @Override
    def prepare() {
        super.prepare()

        fileArchiver = new FileArchiver(steps, config)

        if (!config.dotnet.sdkVersion) {
            steps.error "sdkVersion is empty!"
        }
        if (!config.dotnet.buildFile) {
            steps.error "buildFile is empty!"
        }
        if (!config.dotnet.msiConfig) {
            steps.error "msiConfig is empty!"
        }

        config.parallelUtAnalysis = false

        String configuration = (config.dotnet.configuration as String).toLowerCase()
        if (configuration != 'debug' && configuration != 'release') {
            steps.error "configuration is invalid!"
        }

        vmWorkspace = (steps.WORKSPACE as String).replace("/home/jenkins/agent", "R:").replace("/", "\\")

        steps.container('builder') {
            String workloads = ''
            if (config.dotnet.workloads) {
                workloads = config.dotnet.workloads.join(',')
            }

            String cmd
            if (config.dotnet.useRemoteBuilder) {
                smbServerAddress = "\\\\127.0.0.1\\${config._system.smbServer.shareName} ${config._system.smbServer.password} /user:${config._system.smbServer.user}"

                String host = (config._system.smbServer.external as String).split(':')[0]
                String port = (config._system.smbServer.external as String).split(':')[1]
                cmd = """
\$ProgressPreference = "SilentlyContinue"
${config.debugPowershell}

netsh interface portproxy delete v4tov4 listenport=445 | out-null
\$ErrorActionPreference = "Stop"
netsh interface portproxy add v4tov4 listenport=445 connectaddress=${host} connectport=${port}
"""
                steps.writeFile(file: '~ido-cluster.ps1', text: cmd, encoding: "UTF-8")
                steps.sh """
                        currentHome=\$HOME
                        sudo -- sh -c "cat \${currentHome}/hosts >> /etc/hosts"
                        
                        scp ${steps.WORKSPACE}/~ido-cluster.ps1 remote-host:/c:/
                        ssh remote-host "c:/~ido-cluster.ps1"
                    """
            } else {
                smbServerAddress = "\\\\${config._system.smbServer.internal}\\${config._system.smbServer.shareName} ${config._system.smbServer.password} /user:${config._system.smbServer.user}"
                steps.sh """
                        if [[ \$(grep -E -c '(svm|vmx)' /proc/cpuinfo) -le 0 ]]; then
                            echo KVM not possible on this host
                            exit 1
                        fi
                        
                        sudo -- sh -c "echo '127.0.0.1 remote-host' >> /etc/hosts"
                    """
            }

            cmd = """
powershell -command \"New-ItemProperty -Path 'HKLM:\\SOFTWARE\\OpenSSH' -Name DefaultShell -Value 'C:\\Windows\\System32\\WindowsPowerShell\\v1.0\\powershell.exe' -PropertyType String -Force; Set-ExecutionPolicy -ExecutionPolicy Bypass -Scope CurrentUser\"
"""
            steps.writeFile(file: '~ido-cluster.ps1', text: cmd, encoding: "UTF-8")
            steps.sh """
                    scp ${steps.WORKSPACE}/~ido-cluster.ps1 remote-host:/c:/
                    ssh remote-host "c:/~ido-cluster.ps1"
                """

            String branch = Utils.getBranchName(steps)
            cmd = """
\$ErrorActionPreference = "Stop"
\$ProgressPreference = "SilentlyContinue"
${config.debugPowershell}

[Environment]::SetEnvironmentVariable('DOTNET_CLI_TELEMETRY_OPTOUT','true', 'User')
[Environment]::SetEnvironmentVariable('CI_PRODUCTNAME','$config.productName', 'User')
[Environment]::SetEnvironmentVariable('CI_BRANCH','$branch', 'User')
[Environment]::SetEnvironmentVariable('CI_CONFIGURATION','$config.dotnet.configuration', 'User')
[Environment]::SetEnvironmentVariable('WORKSPACE','$vmWorkspace', 'User')
[Environment]::SetEnvironmentVariable('NUGET_PACKAGES','\$NUGET_PACKAGES', 'User')
[Environment]::SetEnvironmentVariable('NUGET_HTTP_CACHE_PATH','\$NUGET_HTTP_CACHE_PATH', 'User')
[Environment]::SetEnvironmentVariable('NUGET_PLUGINS_CACHE_PATH','\$NUGET_PLUGINS_CACHE_PATH', 'User')
[Environment]::SetEnvironmentVariable('SONAR_USER_HOME',"\$SONAR_USER_HOME", 'User')
"""
            steps.writeFile(file: '~ido-cluster.ps1.template', text: cmd, encoding: "UTF-8")
            steps.sh """
                    envsubst < ${steps.WORKSPACE}/~ido-cluster.ps1.template > ${steps.WORKSPACE}/~ido-cluster.ps1
                    scp ${steps.WORKSPACE}/~ido-cluster.ps1 remote-host:/c:/
                    ssh remote-host "c:/~ido-cluster.ps1"
                """

            cmd = """
\$ErrorActionPreference = "Stop"
\$ProgressPreference = "SilentlyContinue"
${config.debugPowershell}

if (-not(Test-Path -Path R:/workspace -PathType Container))
{
    net use R: ${smbServerAddress}
}

New-Item -ItemType Directory -Force -Path \$Env:NUGET_PACKAGES | out-null
New-Item -ItemType Directory -Force -Path \$Env:NUGET_HTTP_CACHE_PATH | out-null
New-Item -ItemType Directory -Force -Path \$Env:NUGET_PLUGINS_CACHE_PATH | out-null

\$InstallDir = "${config._system.dotnet.sdkPath}"
\$SdkVersion = "${config.dotnet.sdkVersion}"

New-Item -Type "directory" -Path \$InstallDir -force | out-null

if (-not(Test-Path -Path \$InstallDir/dotnet-install.ps1 -PathType Leaf))
{
    Write-Host "Downloading the SDK installer..."
    Invoke-WebRequest `
        -Uri "https://dot.net/v1/dotnet-install.ps1" `
        -OutFile "\$InstallDir/dotnet-install.ps1"
}

Write-Host "Installing the SDK requested version (\$SdkVersion) ..."
& \$InstallDir/dotnet-install.ps1 -Channel \$SdkVersion -InstallDir "\$InstallDir/\$SdkVersion"

\$workloadsStr = "${workloads}"
if (\$workloadsStr.Length -ne 0)
{
    \$workloads = \$workloadsStr.Split(',')
    foreach (\$wl in \$workloads)
    {
        Write-Host "Installing workload: \$wl ..."
        dotnet workload install \$wl --source ${config._system.dotnet.nugetSource}
    }
}
"""
            steps.writeFile(file: '~ido-cluster.ps1', text: cmd, encoding: "UTF-8")
            steps.sh """
                    scp ${steps.WORKSPACE}/~ido-cluster.ps1 remote-host:/c:/
                    # Win env only available from the next session
                    ssh remote-host "c:/~ido-cluster.ps1"
                """
        }
    }

    @Override
    def afterScm() {
        steps.container('builder') {
            String cmd = """
\$ErrorActionPreference = "Stop"
\$ProgressPreference = "SilentlyContinue"
${config.debugPowershell}

if (-not(Test-Path -Path R:/workspace -PathType Container))
{
    net use R: ${smbServerAddress}
}

cd "${vmWorkspace}/${config.srcRootPath}"
./${config.customerBuildScript.afterScm}
"""
            steps.writeFile(file: '~ido-cluster.ps1', text: cmd, encoding: "UTF-8")

            steps.sh """
                scp ${steps.WORKSPACE}/~ido-cluster.ps1 remote-host:/c:/
                ssh remote-host "c:/~ido-cluster.ps1"
            """
        }
    }

    @Override
    def versioning() {
        super.versioning()

        steps.container('builder') {
            steps.sh """
                ssh remote-host "[Environment]::SetEnvironmentVariable('CI_VERSION','$config.version', 'User')"
            """
        }

    }

    @Override
    def afterVersioning() {
        steps.container('builder') {
            String cmd = """
\$ErrorActionPreference = "Stop"
\$ProgressPreference = "SilentlyContinue"
${config.debugPowershell}

if (-not(Test-Path -Path R:/workspace -PathType Container))
{
    net use R: ${smbServerAddress}
}

cd "${vmWorkspace}/${config.srcRootPath}"
./${config.customerBuildScript.afterVersioning}
"""
            steps.writeFile(file: '~ido-cluster.ps1', text: cmd, encoding: "UTF-8")

            steps.sh """
                scp ${steps.WORKSPACE}/~ido-cluster.ps1 remote-host:/c:/
                ssh remote-host "c:/~ido-cluster.ps1"
            """

            if (steps.fileExists("${config.srcRootPath}/ido-cluster/_version")) {
                config.version = steps.readFile(file: "${config.srcRootPath}/ido-cluster/_version", encoding: "UTF-8").trim()

                steps.sh """
                    ssh remote-host "[Environment]::SetEnvironmentVariable('CI_VERSION','$config.version', 'User')"
                """
            }
        }
    }

    @Override
    def ut() {
        if (!config.dotnet.ut.enabled) {
            return
        }

        if (!config.dotnet.ut.project) {
            steps.error "ut.project is empty!"
        }

        steps.container('builder') {
            String cmd = """
\$ErrorActionPreference = "Stop"
\$ProgressPreference = "SilentlyContinue"
${config.debugPowershell}

if (-not(Test-Path -Path R:/workspace -PathType Container))
{
    net use R: ${smbServerAddress}
}

\$Env:Path = "${config._system.dotnet.sdkPath}/${config.dotnet.sdkVersion};\$Env:Path"

cd "${vmWorkspace}/${config.srcRootPath}"

dotnet publish ${config.dotnet.ut.project} -c ${config.dotnet.configuration} -p:Version=${config.version} `
    --source ${config._system.dotnet.nugetSource} --no-cache --nologo

dotnet test ${config.dotnet.ut.project} -c ${config.dotnet.configuration} --no-build --nologo `
    --collect:"XPlat Code Coverage" --results-directory "${config.srcRootPath}/TestResults"
"""

            steps.writeFile(file: '~ido-cluster.ps1', text: cmd, encoding: "UTF-8")
            steps.sh """
                scp ${steps.WORKSPACE}/~ido-cluster.ps1 remote-host:/c:/
                ssh remote-host "c:/~ido-cluster.ps1"
            """

            steps.recordCoverage(tools: [[parser: 'COBERTURA', pattern: '**/coverage.cobertura.xml']],
                    failOnError: true, sourceCodeRetention: 'NEVER',
                    qualityGates: [[criticality: 'FAILURE', metric: 'LINE', threshold: config.dotnet.ut.lineCoverageThreshold]])
            if (steps.currentBuild.result == 'FAILURE') {
                steps.error "UT coverage failure!"
            }
        }
    }

    @Override
    def codeAnalysis() {
        if (!config.dotnet.codeAnalysisEnabled) {
            return
        }

        steps.container('builder') {
            String cmd = ""
            steps.withSonarQubeEnv(config.nodejs.sonarqubeServerName) {
                String qualityGate = ""
                int timeoutSecond = config.dotnet.sonarqubeTimeoutMinutes * 60
                if (config.dotnet.qualityGateEnabled) {
                    qualityGate = "/d:sonar.qualitygate.wait=true /d:sonar.qualitygate.timeout=${timeoutSecond}"
                }

                cmd = """
\$ErrorActionPreference = "Stop"
\$ProgressPreference = "SilentlyContinue"
${config.debugPowershell}

if (-not(Test-Path -Path R:/workspace -PathType Container))
{
    net use R: ${smbServerAddress}
}

\$Env:Path = "${config._system.dotnet.sdkPath}/${config.dotnet.sdkVersion};${config._system.dotnet.sdkPath}/tools;\$Env:Path"

dotnet tool install dotnet-sonarscanner --add-source ${config._system.dotnet.nugetSource} --ignore-failed-sources `
    --tool-path "${config._system.dotnet.sdkPath}/tools" --no-cache

cd "${vmWorkspace}/${config.srcRootPath}"

dotnet sonarscanner begin /k:"${config.productName}" /d:sonar.login="${steps.env.SONAR_AUTH_TOKEN}" `
    /d:sonar.host.url="${steps.env.SONAR_HOST_URL}" ${qualityGate}
dotnet build ${config.dotnet.buildFile} -c ${config.dotnet.configuration} -p:Version=${config.version} `
    --source ${config._system.dotnet.nugetSource} --no-cache --nologo
dotnet sonarscanner end /d:sonar.login="${steps.env.SONAR_AUTH_TOKEN}"
if (-not\$?)
{
    throw 'Code analysis failure'
}
"""

                steps.writeFile(file: '~ido-cluster.ps1', text: cmd, encoding: "UTF-8")
                steps.sh """
                    scp ${steps.WORKSPACE}/~ido-cluster.ps1 remote-host:/c:/
                    ssh remote-host "c:/~ido-cluster.ps1"
                """
            }
        }
    }

    @Override
    def beforeBuild() {
        steps.container('builder') {
            String cmd = """
\$ErrorActionPreference = "Stop"
\$ProgressPreference = "SilentlyContinue"
${config.debugPowershell}

if (-not(Test-Path -Path R:/workspace -PathType Container))
{
    net use R: ${smbServerAddress}
}

cd "${vmWorkspace}/${config.srcRootPath}"
./${config.customerBuildScript.beforeBuild}
"""
            steps.writeFile(file: '~ido-cluster.ps1', text: cmd, encoding: "UTF-8")

            steps.sh """
                scp ${steps.WORKSPACE}/~ido-cluster.ps1 remote-host:/c:/
                ssh remote-host "c:/~ido-cluster.ps1"
            """
        }
    }

    @Override
    Boolean customerBuild() {
        Boolean runCustomer
        steps.container('builder') {
            if (steps.fileExists("${steps.WORKSPACE}/${config.srcRootPath}/${config.customerBuildScript.build}")) {
                steps.echo "Execute customer build script: ${config.customerBuildScript.build}"
                String cmd = """
\$ErrorActionPreference = "Stop"
\$ProgressPreference = "SilentlyContinue"
${config.debugPowershell}

if (-not(Test-Path -Path R:/workspace -PathType Container))
{
    net use R: ${smbServerAddress}
}

cd "${vmWorkspace}/${config.srcRootPath}"
./${config.customerBuildScript.build}
"""
                steps.writeFile(file: '~ido-cluster.ps1', text: cmd, encoding: "UTF-8")

                steps.sh """
                    scp ${steps.WORKSPACE}/~ido-cluster.ps1 remote-host:/c:/
                    ssh remote-host "c:/~ido-cluster.ps1"
                """
                runCustomer = true
            } else {
                runCustomer = false
            }
        }
        return runCustomer
    }

    @Override
    def build() {
        String newFileName = fileArchiver.getFileName()

        String cmdRuntime = ""
        if (config.dotnet.runtime) {
            cmdRuntime = "--runtime ${config.dotnet.runtime}"
        }
        steps.container('builder') {
            String cmd = """
\$ErrorActionPreference = "Stop"
\$ProgressPreference = "SilentlyContinue"
${config.debugPowershell}

if (-not(Test-Path -Path R:/workspace -PathType Container))
{
    net use R: ${smbServerAddress}
}

\$Env:Path = "${config._system.dotnet.sdkPath}/${config.dotnet.sdkVersion};\$Env:Path"

cd "${vmWorkspace}/${config.srcRootPath}"

dotnet publish ${config.dotnet.buildFile} -c ${config.dotnet.configuration} -p:Version=${config.version} `
    --source ${config._system.dotnet.nugetSource} ${cmdRuntime} --no-cache --nologo

envsubst -i "${config.dotnet.msiConfig}" -o "${config.dotnet.msiConfig}.final" -no-unset -no-empty
if (-not\$?)
{
    throw 'envsubst failure'
}

New-Item -ItemType Directory -Force -Path "ido-cluster/outputs"
wixc.ps1 -config "${config.dotnet.msiConfig}.final" -output "ido-cluster/outputs/${newFileName}.msi"
"""
            steps.writeFile(file: '~ido-cluster.ps1', text: cmd, encoding: "UTF-8")

            steps.sh """
                scp ${steps.WORKSPACE}/~ido-cluster.ps1 remote-host:/c:/
                ssh remote-host "c:/~ido-cluster.ps1"
            """
        }
    }


    @Override
    def afterBuild() {
        steps.container('builder') {
            String cmd = """
\$ErrorActionPreference = "Stop"
\$ProgressPreference = "SilentlyContinue"
${config.debugPowershell}

if (-not(Test-Path -Path R:/workspace -PathType Container))
{
    net use R: ${smbServerAddress}
}

cd "${vmWorkspace}/${config.srcRootPath}"
./${config.customerBuildScript.afterBuild}
"""
            steps.writeFile(file: '~ido-cluster.ps1', text: cmd, encoding: "UTF-8")

            steps.sh """
                scp ${steps.WORKSPACE}/~ido-cluster.ps1 remote-host:/c:/
                ssh remote-host "c:/~ido-cluster.ps1"
            """
        }
    }

    @Override
    def archive() {
        String uploadUrl = "${config.fileServer.uploadUrl}${config.fileServer.uploadRootPath}${config.productName}/" +
                Utils.getBranchName(steps) + "/dotnet"

        fileArchiver.upload(uploadUrl, "${config.srcRootPath}/ido-cluster/outputs")
    }

    @Override
    def afterArchive() {
        steps.container('builder') {
            String cmd = """
\$ErrorActionPreference = "Stop"
\$ProgressPreference = "SilentlyContinue"
${config.debugPowershell}

if (-not(Test-Path -Path R:/workspace -PathType Container))
{
    net use R: ${smbServerAddress}
}

cd "${vmWorkspace}/${config.srcRootPath}"
./${config.customerBuildScript.afterArchive}
"""
            steps.writeFile(file: '~ido-cluster.ps1', text: cmd, encoding: "UTF-8")

            steps.sh """
                scp ${steps.WORKSPACE}/~ido-cluster.ps1 remote-host:/c:/
                ssh remote-host "c:/~ido-cluster.ps1"
            """
        }
    }
}
