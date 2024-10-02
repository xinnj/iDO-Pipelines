package com.ido.pipeline.app

import com.ido.pipeline.Utils
import com.ido.pipeline.archiver.FileArchiver
import com.ido.pipeline.builderBase.WinPipeline

/**
 * @author xinnj
 */
class DotnetAppPipeline extends WinPipeline {
    FileArchiver fileArchiver

    DotnetAppPipeline(Object steps) {
        super(steps)
        this.useK8sAgent = false
        this.nodeName = "win"
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

        String workloads = ''
        if (config.dotnet.workloads) {
            workloads = config.dotnet.workloads.join(',')
        }

        steps.pwsh """${config.debugPwsh}
            \$SdkVersion = "${config.dotnet.sdkVersion}"
            
            if (-not(Test-Path -Path "\$Env:LocalAppData/Microsoft/dotnet-install.ps1" -PathType Leaf))
            {
                Write-Host "Downloading the SDK installer..."
                Invoke-WebRequest `
                    -Uri "https://dot.net/v1/dotnet-install.ps1" `
                    -OutFile "\$Env:LocalAppData/Microsoft/dotnet-install.ps1"
            }
            
            Write-Host "Installing the SDK requested version (\$SdkVersion) ..."
            & "\$Env:LocalAppData/Microsoft/dotnet-install.ps1" -Channel \$SdkVersion
            
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
    }

    @Override
    def ut() {
        if (!config.dotnet.ut.enabled) {
            return
        }

        if (!config.dotnet.ut.project) {
            steps.error "ut.project is empty!"
        }

        steps.pwsh """${config.debugPwsh}            
            cd "${steps.WORKSPACE}/${config.srcRootPath}"
            dotnet publish ${config.dotnet.ut.project} -c ${config.dotnet.configuration} -p:Version=${config.version} `
                --source ${config._system.dotnet.nugetSource} --no-cache --nologo
            
            dotnet test ${config.dotnet.ut.project} -c ${config.dotnet.configuration} --no-build --nologo `
                --collect:"XPlat Code Coverage" --results-directory "${config.srcRootPath}/TestResults"
        """

        steps.recordCoverage(tools: [[parser: 'COBERTURA', pattern: '**/coverage.cobertura.xml']],
                failOnError: true, sourceCodeRetention: 'NEVER',
                qualityGates: [[criticality: 'FAILURE', metric: 'LINE', threshold: config.dotnet.ut.lineCoverageThreshold]])
        if (steps.currentBuild.result == 'FAILURE') {
            steps.error "UT coverage failure!"
        }
    }

    @Override
    def codeAnalysis() {
        if (!config.dotnet.codeAnalysisEnabled) {
            return
        }

        steps.withSonarQubeEnv(config.nodejs.sonarqubeServerName) {
            String qualityGate = ""
            int timeoutSecond = config.dotnet.sonarqubeTimeoutMinutes * 60
            if (config.dotnet.qualityGateEnabled) {
                qualityGate = "/d:sonar.qualitygate.wait=true /d:sonar.qualitygate.timeout=${timeoutSecond}"
            }

            steps.pwsh """${config.debugPwsh}
                dotnet tool install dotnet-sonarscanner --add-source ${config._system.dotnet.nugetSource} --ignore-failed-sources --no-cache
                
                cd "${steps.WORKSPACE}/${config.srcRootPath}"
                
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
        }
    }

    @Override
    def build() {
        String cmdRuntime = ""
        if (config.dotnet.runtime) {
            cmdRuntime = "--runtime ${config.dotnet.runtime}"
        }
        steps.pwsh """${config.debugPwsh}
            cd "${steps.WORKSPACE}/${config.srcRootPath}"
            
            dotnet publish ${config.dotnet.buildFile} -c ${config.dotnet.configuration} -p:Version=${config.version} `
                --source ${config._system.dotnet.nugetSource} ${cmdRuntime} --no-cache --nologo
        """
    }

    @Override
    def archive() {
        String newFileName = fileArchiver.getFileName()
        steps.pwsh """${config.debugPwsh}
            cd "${steps.WORKSPACE}/${config.srcRootPath}"
            
            envsubst -i "${config.dotnet.msiConfig}" -o "${config.dotnet.msiConfig}.final" -no-unset -no-empty
            if (-not\$?)
            {
                throw 'envsubst failure'
            }
            
            New-Item -ItemType Directory -Force -Path "ido-cluster/outputs"
            wixc.ps1 -config "${config.dotnet.msiConfig}.final" -output "ido-cluster/outputs/${newFileName}.msi"
        """

        String uploadUrl = "${config.fileServer.uploadUrl}${config.fileServer.uploadRootPath}${config.productName}/" +
                config.branch + "/dotnet"

        fileArchiver.upload(uploadUrl, "${config.srcRootPath}/ido-cluster/outputs")
    }
}
