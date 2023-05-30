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
        if (this.customerBuild()) {
            return
        }

        steps.container('builder') {
            steps.sh """
                export NODE_ENV=production
                cd "${config.srcRootPath}"

                npm ci --omit=dev
            """
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

        this.buildImage()
    }
}
