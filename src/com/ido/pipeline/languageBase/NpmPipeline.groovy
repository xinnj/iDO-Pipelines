package com.ido.pipeline.languageBase

import com.ido.pipeline.base.BuildPipeline

/**
 * @author xinnj
 */
abstract class NpmPipeline extends BuildPipeline {
    NpmPipeline(Object steps) {
        super(steps)
    }

    @Override
    Map runPipeline(Map config) {
        config.parallelUtAnalysis = true

        String npmBuilder = steps.libraryResource(resource: 'pod-template/npm-builder.yaml', encoding: 'UTF-8')
        npmBuilder = npmBuilder.replaceAll('<builderImage>', config.npm.builderImage)
        config.podTemplate = npmBuilder

        return super.runPipeline(config)
    }

    @Override
    def scm() {
        super.scm()

        if (config.npm.useDefaultNpmrc) {
            String npmrc = steps.libraryResource(resource: 'builder/default-npmrc', encoding: 'UTF-8')
            steps.writeFile(file: "${config.srcRootPath}/.npmrc", text: npmrc, encoding: "UTF-8")
        }

        steps.container('builder') {
            steps.sh """${config.debugSh}
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
        if (!config.context.utEnabled) {
            return
        }

        steps.container('builder') {
            switch (config.utTool) {
                case "jest":
                    steps.sh """${config.debugSh}
                        cd "${config.srcRootPath}"
                        npm pkg set jest.coverageReporters[]=text
                        npm pkg set jest.coverageReporters[]=cobertura
                        npm install-test
                    """
                    break
                case "vitest":
                    /* Following config is needed in vite.config.js

                    test: {
                        coverage: {
                            provider: 'istanbul',
                            reporter: ['text', 'cobertura']
                        },
                    }

                    */
                    steps.sh """${config.debugSh}
                        cd "${config.srcRootPath}"
                        npm i -D @vitest/coverage-istanbul
                        npm install-test
                    """
                    break
                default:
                    steps.error "The UT tool ${config.utTool} is not supported!"
            }

            steps.recordCoverage(tools: [[parser: 'COBERTURA', pattern: '**/cobertura-coverage.xml']],
                    failOnError: true, sourceCodeRetention: 'NEVER',
                    qualityGates: [[criticality: 'FAILURE', metric: 'LINE', threshold: config.context.lineCoverageThreshold]])
            if (steps.currentBuild.result == 'FAILURE') {
                steps.error "UT coverage failure!"
            }
        }
    }

    @Override
    def codeAnalysis() {
        if (!config.context.codeAnalysisEnabled) {
            return
        }

        steps.container('sonar-scanner') {
            steps.withSonarQubeEnv(config.context.sonarqubeServerName) {
                steps.sh """${config.debugSh}
                    cd "${config.srcRootPath}"
                    sonar-scanner -Dsonar.projectKey=${config.productName} -Dsonar.sourceEncoding=UTF-8
                """
            }
        }

        if (config.context.qualityGateEnabled) {
            steps.timeout(time: config.context.sonarqubeTimeoutMinutes, unit: 'MINUTES') {
                def qg = steps.waitForQualityGate()
                if (qg.status != 'OK') {
                    steps.error "Quality gate failure: ${qg.status}"
                }
            }
        }
    }
}
