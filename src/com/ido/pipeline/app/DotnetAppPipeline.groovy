package com.ido.pipeline.app

import com.ido.pipeline.Utils
import com.ido.pipeline.base.Artifact
import org.apache.ivy.core.module.descriptor.OverrideDependencyDescriptorMediator

/**
 * @author xinnj
 */
class DotnetAppPipeline extends AppPipeline {
    String vmWorkspace = ""
    String smbServerAddress = ""

    DotnetAppPipeline(Object steps) {
        super(steps)
    }

    @Override
    Map runPipeline(Map config) {
        config.nodeType = "k8s"
        config.parallelUtAnalysis = false

        String builder = steps.libraryResource(resource: 'pod-template/win-builder.yaml', encoding: 'UTF-8')
        builder = builder.replaceAll('<builderImage>', config.dotnet.builderImage)
                .replaceAll('<winImage>', config.dotnet.winImage)
        config.podTemplate = builder

        return super.runPipeline(config)
    }

    @Override
    def prepare() {
        super.prepare()

        if (!config.dotnet.sdkVersion) {
            steps.error "sdkVersion is empty!"
        }
        if (!config.dotnet.buildFile) {
            steps.error "buildFile is empty!"
        }
        if (!config.dotnet.msiConfig) {
            steps.error "msiConfig is empty!"
        }

        String configuration = (config.dotnet.configuration as String).toLowerCase()
        if (configuration != 'debug' && configuration != 'release') {
            steps.error "configuration is invalid!"
        }

        if (config.nodeType == "k8s") {
            vmWorkspace = (steps.WORKSPACE as String).replace("/home/jenkins/agent", "R:").replace("/", "\\")
            smbServerAddress = "\\\\${config._system.smbServer.internal}\\${config._system.smbServer.shareName} ${config._system.smbServer.password} /user:${config._system.smbServer.user}"

            steps.container('builder') {
                String workloads = ''
                if (config.dotnet.workloads) {
                    workloads = config.dotnet.workloads.join(',')
                }

                String cmd = """
\$ErrorActionPreference = "Stop"
\$ProgressPreference = "SilentlyContinue"
Set-PSDebug -Strict -Trace 0

net use R: ${smbServerAddress}

New-Item -ItemType Directory -Force -Path \$Env:NUGET_PACKAGES | out-null
New-Item -ItemType Directory -Force -Path \$Env:NUGET_HTTP_CACHE_PATH | out-null
New-Item -ItemType Directory -Force -Path \$Env:NUGET_PLUGINS_CACHE_PATH | out-null

\$InstallDir = "${config._system.dotnetSdkPath}"
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
        dotnet workload install \$wl --source ${config._system.nugetSource}
    }
}
"""
                steps.writeFile(file: '~ido-cluster.ps1', text: cmd, encoding: "UTF-8")

                String branch = Utils.getBranchName(steps)
                steps.sh """
                    if [ \$(grep -E -c '(svm|vmx)' /proc/cpuinfo) -le 0 ]; then
                        echo KVM not possible on this host
                        exit 1
                    fi

                    ssh 127.0.0.1 << EOF
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

                steps.sh """
                    scp ${steps.WORKSPACE}/~ido-cluster.ps1 127.0.0.1:/c:/
                    ssh 127.0.0.1 "c:/~ido-cluster.ps1"
                """
            }
        }
    }

    @Override
    def versioning() {
        super.versioning()

        String branch = Utils.getBranchName(steps)
        steps.container('builder') {
            steps.sh """
                ssh 127.0.0.1 "[Environment]::SetEnvironmentVariable('CI_VERSION','$config.version', 'User')"
            """
        }

    }

    @Override
    def afterScm() {
        steps.container('builder') {
            String cmd = """
\$ErrorActionPreference = "Stop"
\$ProgressPreference = "SilentlyContinue"
Set-PSDebug -Strict -Trace 0

net use R: ${smbServerAddress}

cd "${vmWorkspace}/${config.srcRootPath}"
./${config.customerBuildScript.afterScm}
"""
            steps.writeFile(file: '~ido-cluster.ps1', text: cmd, encoding: "UTF-8")

            steps.sh """
                scp ${steps.WORKSPACE}/~ido-cluster.ps1 127.0.0.1:/c:/
                ssh 127.0.0.1 "c:/~ido-cluster.ps1"
            """

            config.version = steps.readFile(file: "${config.srcRootPath}/ido-cluster/_version", encoding: "UTF-8").trim()

            steps.sh """
                ssh 127.0.0.1 "[Environment]::SetEnvironmentVariable('CI_VERSION','$config.version', 'User')"
            """

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
Set-PSDebug -Strict -Trace 0

net use R: ${smbServerAddress}

\$Env:Path = "${config._system.dotnetSdkPath}/${config.dotnet.sdkVersion};\$Env:Path"

cd "${vmWorkspace}/${config.srcRootPath}"

dotnet publish ${config.dotnet.ut.project} -c ${config.dotnet.configuration} -p:Version=${config.version} `
    --source ${config._system.nugetSource} --no-cache --nologo

dotnet test ${config.dotnet.ut.project} -c ${config.dotnet.configuration} --no-build --nologo `
    --collect:"XPlat Code Coverage" --results-directory "${config.srcRootPath}/TestResults"
"""

            steps.writeFile(file: '~ido-cluster.ps1', text: cmd, encoding: "UTF-8")
            steps.sh """
                scp ${steps.WORKSPACE}/~ido-cluster.ps1 127.0.0.1:/c:/
                ssh 127.0.0.1 "c:/~ido-cluster.ps1"
            """

            def files
            steps.dir("${steps.WORKSPACE}/${config.srcRootPath}") {
                files = steps.findFiles(glob: "TestResults/**/coverage.cobertura.xml")
            }
            if (files) {
                steps.echo "Coverage report file: ${files[0].path}"
                steps.cobertura(coberturaReportFile: files[0].path, enableNewApi: true,
                        lineCoverageTargets: "${config.dotnet.ut.lineCoverageThreshold}, ${config.dotnet.ut.lineCoverageThreshold}, ${config.dotnet.ut.lineCoverageThreshold}")
            } else {
                steps.error "Can't find coverage report file!"
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
Set-PSDebug -Strict -Trace 0

net use R: ${smbServerAddress}

\$Env:Path = "${config._system.dotnetSdkPath}/${config.dotnet.sdkVersion};${config._system.dotnetSdkPath}/tools;\$Env:Path"

dotnet tool install dotnet-sonarscanner --add-source ${config._system.nugetSource} --ignore-failed-sources `
    --tool-path "${config._system.dotnetSdkPath}/tools" --no-cache

cd "${vmWorkspace}/${config.srcRootPath}"

dotnet sonarscanner begin /k:"${config.productName}" /d:sonar.login="${steps.env.SONAR_AUTH_TOKEN}" `
    /d:sonar.host.url="${steps.env.SONAR_HOST_URL}" ${qualityGate}
dotnet build ${config.dotnet.buildFile} -c ${config.dotnet.configuration} -p:Version=${config.version} `
    --source ${config._system.nugetSource} --no-cache --nologo
dotnet sonarscanner end /d:sonar.login="${steps.env.SONAR_AUTH_TOKEN}"
if (-not\$?)
{
    throw 'Code analysis failure'
}
"""

                steps.writeFile(file: '~ido-cluster.ps1', text: cmd, encoding: "UTF-8")
                steps.sh """
                    scp ${steps.WORKSPACE}/~ido-cluster.ps1 127.0.0.1:/c:/
                    ssh 127.0.0.1 "c:/~ido-cluster.ps1"
                """
            }
        }
    }

    @Override
    def beforeBuild() {
        steps.container('builder') {
            String branch = Utils.getBranchName(steps)

            String cmd = """
\$ErrorActionPreference = "Stop"
\$ProgressPreference = "SilentlyContinue"
Set-PSDebug -Strict -Trace 0

net use R: ${smbServerAddress}

cd "${vmWorkspace}/${config.srcRootPath}"
./${config.customerBuildScript.beforeBuild}
"""
            steps.writeFile(file: '~ido-cluster.ps1', text: cmd, encoding: "UTF-8")

            steps.sh """
                scp ${steps.WORKSPACE}/~ido-cluster.ps1 127.0.0.1:/c:/
                ssh 127.0.0.1 "c:/~ido-cluster.ps1"
            """
        }
    }

    @Override
    Boolean customerBuild() {
        steps.container('builder') {
            if (steps.fileExists("${steps.WORKSPACE}/${config.srcRootPath}/${config.customerBuildScript.build}")) {
                steps.echo "Execute customer build script: ${config.customerBuildScript.build}"
                String branch = Utils.getBranchName(steps)

                String cmd = """
\$ErrorActionPreference = "Stop"
\$ProgressPreference = "SilentlyContinue"
Set-PSDebug -Strict -Trace 0

net use R: ${smbServerAddress}

cd "${vmWorkspace}/${config.srcRootPath}"
./${config.customerBuildScript.build}
"""
                steps.writeFile(file: '~ido-cluster.ps1', text: cmd, encoding: "UTF-8")

                steps.sh """
                    scp ${steps.WORKSPACE}/~ido-cluster.ps1 127.0.0.1:/c:/
                    ssh 127.0.0.1 "c:/~ido-cluster.ps1"
                """
            }
        }
    }

    @Override
    def build() {
        if (this.customerBuild()) {
            return
        }

        String newFileName = this.getFileName()

        String cmdRuntime = ""
        if (config.dotnet.runtime) {
            cmdRuntime = "--runtime ${config.dotnet.runtime}"
        }
        steps.container('builder') {
            String cmd = """
\$ErrorActionPreference = "Stop"
\$ProgressPreference = "SilentlyContinue"
Set-PSDebug -Strict -Trace 0

net use R: ${smbServerAddress}

\$Env:Path = "${config._system.dotnetSdkPath}/${config.dotnet.sdkVersion};\$Env:Path"

cd "${vmWorkspace}/${config.srcRootPath}"

dotnet publish ${config.dotnet.buildFile} -c ${config.dotnet.configuration} -p:Version=${config.version} `
    --source ${config._system.nugetSource} ${cmdRuntime} --no-cache --nologo

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
                scp ${steps.WORKSPACE}/~ido-cluster.ps1 127.0.0.1:/c:/
                ssh 127.0.0.1 "c:/~ido-cluster.ps1"
            """
        }
    }

    @Override
    def afterBuild() {
        steps.container('builder') {
            String branch = Utils.getBranchName(steps)

            String cmd = """
\$ErrorActionPreference = "Stop"
\$ProgressPreference = "SilentlyContinue"
Set-PSDebug -Strict -Trace 0

net use R: ${smbServerAddress}

cd "${vmWorkspace}/${config.srcRootPath}"
./${config.customerBuildScript.afterBuild}
"""
            steps.writeFile(file: '~ido-cluster.ps1', text: cmd, encoding: "UTF-8")

            steps.sh """
                scp ${steps.WORKSPACE}/~ido-cluster.ps1 127.0.0.1:/c:/
                ssh 127.0.0.1 "c:/~ido-cluster.ps1"
            """
        }
    }

    @Override
    def archive() {
        String uploadUrl = "${config.fileServer.uploadUrl}${config.fileServer.uploadRootPath}${config.productName}/" +
                Utils.getBranchName(steps) + "/dotnet"

        Artifact artifact = new Artifact()
        artifact.uploadToFileServer(steps, uploadUrl, "${config.srcRootPath}/ido-cluster/outputs")
    }

    String getFileName() {
        String branch = Utils.getBranchName(steps)
        return "${config.productName}-${branch}-${config.version}" as String
    }
}
