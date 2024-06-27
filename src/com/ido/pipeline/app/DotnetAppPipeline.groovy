package com.ido.pipeline.app

import com.ido.pipeline.Utils
import com.ido.pipeline.archiver.FileArchiver
import com.ido.pipeline.builderBase.WinPipeline

/**
 * @author xinnj
 */
class DotnetAppPipeline extends WinPipeline {
    FileArchiver fileArchiver
    String nugetPackages = "R:/nuget/packages"
    String nugetHttpCachePath = "R:/nuget/v3-cache"
    String nugetPluginsCachePath = "R:/nuget/plugins-cache"
    String sonarUserHome = "R:/sonar"

    DotnetAppPipeline(Object steps) {
        super(steps)
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

        steps.container('builder') {
            String workloads = ''
            if (config.dotnet.workloads) {
                workloads = config.dotnet.workloads.join(',')
            }

            String cmd = """
[Environment]::SetEnvironmentVariable('NUGET_PACKAGES','$nugetPackages', 'User')
[Environment]::SetEnvironmentVariable('NUGET_HTTP_CACHE_PATH','$nugetHttpCachePath', 'User')
[Environment]::SetEnvironmentVariable('NUGET_PLUGINS_CACHE_PATH','$nugetPluginsCachePath', 'User')
[Environment]::SetEnvironmentVariable('SONAR_USER_HOME',"$sonarUserHome", 'User')
"""
            Utils.execRemoteWin(steps, config, cmd)

            cmd = """
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
            Utils.execRemoteWin(steps, config, cmd)
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
\$Env:Path = "${config._system.dotnet.sdkPath}/${config.dotnet.sdkVersion};\$Env:Path"

cd "\${Env:WORKSPACE}/${config.srcRootPath}"
dotnet publish ${config.dotnet.ut.project} -c ${config.dotnet.configuration} -p:Version=${config.version} `
    --source ${config._system.dotnet.nugetSource} --no-cache --nologo

dotnet test ${config.dotnet.ut.project} -c ${config.dotnet.configuration} --no-build --nologo `
    --collect:"XPlat Code Coverage" --results-directory "${config.srcRootPath}/TestResults"
"""
            Utils.execRemoteWin(steps, config, cmd)

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
\$Env:Path = "${config._system.dotnet.sdkPath}/${config.dotnet.sdkVersion};${config._system.dotnet.sdkPath}/tools;\$Env:Path"

dotnet tool install dotnet-sonarscanner --add-source ${config._system.dotnet.nugetSource} --ignore-failed-sources `
    --tool-path "${config._system.dotnet.sdkPath}/tools" --no-cache

cd "\${Env:WORKSPACE}/${config.srcRootPath}"

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
                Utils.execRemoteWin(steps, config, cmd)
            }
        }
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
\$Env:Path = "${config._system.dotnet.sdkPath}/${config.dotnet.sdkVersion};\$Env:Path"

cd "\${Env:WORKSPACE}/${config.srcRootPath}"

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
            Utils.execRemoteWin(steps, config, cmd)
        }
    }

    @Override
    def archive() {
        String uploadUrl = "${config.fileServer.uploadUrl}${config.fileServer.uploadRootPath}${config.productName}/" +
                Utils.getBranchName(steps) + "/dotnet"

        fileArchiver.upload(uploadUrl, "${config.srcRootPath}/ido-cluster/outputs")
    }
}
