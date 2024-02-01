package com.ido.pipeline.languageBase

/**
 * @author xinnj
 */
class LanguageNodejs {
    static runPipeline(Map config, Object steps) {
        config.nodeType = "k8s"
        config.parallelUtAnalysis = true

        String nodejsBuilder = steps.libraryResource(resource: 'pod-template/npm-builder.yaml', encoding: 'UTF-8')
        nodejsBuilder = nodejsBuilder.replaceAll('<builderImage>', config.nodejs.baseImage)
        config.podTemplate = nodejsBuilder
    }

    static scm(Map config, Object steps) {
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

    static ut(Map config, Object steps) {
        steps.container('builder') {
            steps.sh """
                cd "${config.srcRootPath}"
                npm pkg set jest.coverageReporters[]=text
                npm pkg set jest.coverageReporters[]=cobertura
                npm install-test
            """

            steps.recordCoverage(tools: [[parser: 'COBERTURA', pattern: '**/cobertura-coverage.xml']],
                    failOnError: true, sourceCodeRetention: 'NEVER',
                    qualityGates: [[criticality: 'FAILURE', metric: 'LINE', threshold: config.nodejs.lineCoverageThreshold]])
            if (steps.currentBuild.result == 'FAILURE') {
                steps.error "UT coverage failure!"
            }
        }
    }

    static codeAnalysis(Map config, Object steps) {
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

    static build(Map config, Object steps) {
        steps.container('builder') {
            steps.sh """
                export NODE_ENV=production
                cd "${config.srcRootPath}"

                npm ci --omit=dev
            """
        }
    }
}
