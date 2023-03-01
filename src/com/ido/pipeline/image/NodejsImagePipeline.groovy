package com.ido.pipeline.image

import com.ido.pipeline.Utils

/**
 * @author xinnj
 */
class NodejsImagePipeline extends ImagePipeline {
    NodejsImagePipeline(Object steps) {
        super(steps)
    }

    @Override
    Map runPipeline(Map config) {
        config.nodeType = "k8s"
        config.parallelUtAnalysis = true

        String nodejsBuilder = steps.libraryResource(resource: 'pod-template/npm-builder.yaml', encoding: 'UTF-8')
        nodejsBuilder = nodejsBuilder.replaceAll('<builderImage>', config.nodejs.baseImage)
        config.podTemplate = nodejsBuilder

        return super.runPipeline(config)
    }

    @Override
    def prepare() {
        super.prepare()

        if (!config.nodejs.baseImage) {
            steps.error "nodejs.baseImage is empty!"
        }
    }

    @Override
    def scm() {
        super.scm()

        if (config.nodejs.useDefaultNpmrc) {
            String npmrc = steps.libraryResource(resource: 'builder/default-npmrc', encoding: 'UTF-8')
            steps.writeFile(file: "${config.srcRootPath}/.npmrc", text: npmrc, encoding: "UTF-8")
        }

        steps.container('builder') {
            steps.sh """
                cd "${config.srcRootPath}"
                echo "npm version:"
                npm -v
                echo "node version:"
                node -v
            """
        }
    }

    @Override
    def ut() {
        if (!config.nodejs.utEnabled) {
            return
        }

        steps.container('builder') {
            steps.sh """
                cd "${config.srcRootPath}"
                npm pkg set jest.coverageReporters[]=text
                npm pkg set jest.coverageReporters[]=cobertura
                # npm pkg set jest.coverageReporters[]=lcov
                # npm pkg set jest.coverageThreshold.global.line=${config.nodejs.lineCoverageThreshold}
                npm install-test
            """

            steps.cobertura(coberturaReportFile: "${config.srcRootPath}/coverage/cobertura-coverage.xml", enableNewApi: true,
                    lineCoverageTargets: "${config.nodejs.lineCoverageThreshold}, ${config.nodejs.lineCoverageThreshold}, ${config.nodejs.lineCoverageThreshold}")

//            steps.publishCoverage(adapters: [steps.istanbulCoberturaAdapter("${config.srcRootPath}/coverage/cobertura-coverage.xml")],
//                    checksName: '', failNoReports: true, failUnhealthy: true, failUnstable: true,
//                    globalThresholds: [[failUnhealthy: true, thresholdTarget: 'Line', unhealthyThreshold: config.nodejs.lineCoverageThreshold, unstableThreshold: config.nodejs.lineCoverageThreshold]],
//                    sourceCodeEncoding: 'UTF-8', sourceFileResolver: steps.sourceFiles('NEVER_STORE'))

//            steps.publishHTML([allowMissing: false, alwaysLinkToLastBuild: false, escapeUnderscores: false, keepAll: false,
//                               reportDir   : "${config.srcRootPath}/coverage/lcov-report", reportFiles: 'index.html',
//                               reportName  : 'Coverage Report', reportTitles: ''])
        }
    }

    @Override
    def codeAnalysis() {
        if (!config.nodejs.codeAnalysisEnabled) {
            return
        }

        steps.container('sonar-scanner') {
            steps.withSonarQubeEnv(config.nodejs.sonarqubeServerName) {
                steps.sh """
                    cd "${config.srcRootPath}"
                    sonar-scanner -Dsonar.projectKey=${config.productName} -Dsonar.sourceEncoding=UTF-8
                """
            }
        }

        if (config.nodejs.qualityGateEnabled) {
            steps.timeout(time: config.nodejs.sonarqubeTimeoutMinutes, unit: 'MINUTES') {
                def qg = steps.waitForQualityGate()
                if (qg.status != 'OK') {
                    steps.error "Quality gate failure: ${qg.status}"
                }
            }
        }
    }

    @Override
    def build() {
        steps.container('builder') {
            steps.withEnv(["CI_PRODUCTNAME=$config.productName",
                           "CI_VERSION=$config.version",
                           "CI_BRANCH=" + Utils.getBranchName(steps)]) {
                steps.sh """
                    export NODE_ENV=production
                    cd "${config.srcRootPath}"
        
                    if [ -s "./${config.buildScript}" ]; then
                        sh "./${config.buildScript}"
                    else
                        npm ci --omit=dev
                    fi
                """
            }
        }

        if (!steps.fileExists("${config.srcRootPath}/.dockerignore")) {
            String dockerignore = steps.libraryResource(resource: 'builder/default-dockerignore', encoding: 'UTF-8')
            steps.writeFile(file: "${config.srcRootPath}/.dockerignore", text: dockerignore, encoding: 'UTF-8')
        }
        if (!config.dockerFile) {
            String dockerfile = steps.libraryResource(resource: 'builder/default-nodejs-dockerfile', encoding: 'UTF-8')
            dockerfile = dockerfile
                    .replaceAll('<baseImage>', config.nodejs.baseImage as String)
                    .replaceAll('<startCmd>', config.nodejs.StartCmd as String)

            if (config._system.imagePullMirror) {
                dockerfile = Utils.replaceImageMirror(dockerfile)
            }

            steps.writeFile(file: "${config.srcRootPath}/Dockerfile", text: dockerfile, encoding: 'UTF-8')
            config.dockerFile = "Dockerfile"
        }

        steps.container('buildah') {
            if (config.registryPull && config.registryPull.credentialsId) {
                steps.withCredentials([steps.usernamePassword(credentialsId: config.registryPull.credentialsId, passwordVariable: 'passwordPull', usernameVariable: 'userNamePull')]) {
                    if (config.registryPush && config.registryPush.credentialsId) {
                        steps.withCredentials([steps.usernamePassword(credentialsId: config.registryPush.credentialsId, passwordVariable: 'passwordPush', usernameVariable: 'userNamePush')]) {
                            runScript()
                        }
                    } else {
                        runScript()
                    }
                }
            } else {
                if (config.registryPush && config.registryPush.credentialsId) {
                    steps.withCredentials([steps.usernamePassword(credentialsId: config.registryPush.credentialsId, passwordVariable: 'passwordPush', usernameVariable: 'userNamePush')]) {
                        runScript()
                    }
                } else {
                    runScript()
                }
            }
        }
    }

    private runScript() {
        String registry_login_pull = ""
        if (config.registryPull && config.registryPull.credentialsId) {
            registry_login_pull = "buildah login -u \${userNamePull}  -p \${passwordPull} " + config.registryPull.url
        }

        String registry_login_push = ""
        if (config.registryPush && config.registryPush.credentialsId) {
            registry_login_push = "buildah login -u \${userNamePush}  -p \${passwordPush} " + config.registryPush.url
        }

        String pushImageFullName = "${config.registryPush.url}/${config.productName}:${config.version}"
        steps.sh """
            cd "${config.srcRootPath}"
            alias buildah="buildah --root /home/jenkins/agent/buildah --runroot /tmp/containers"
            pushImageFullName=${config.registryPush.url}/${config.productName}:${config.version}
            ${registry_login_pull}
            buildah build --format ${config._system.imageFormat} -f ${config.dockerFile} -t ${pushImageFullName} ./
            
            ${registry_login_push}
            buildah push --tls-verify=false ${pushImageFullName}
            buildah rmi ${pushImageFullName}
        """
    }
}
