package com.ido.pipeline.base
/**
 * @author xinnj
 */
abstract class DeployPipeline extends BasePipeline {
    DeployPipeline(steps) {
        super(steps)
    }


    @Override
    def customStages() {
        steps.stage('Prepare') {
            steps.echo "\033[32m########## Stage: Prepare ##########\033[0m"
            this.prepare()
        }

        if (config.deployCheckoutSCM) {
            steps.stage('SCM') {
                steps.echo "\033[32m########## Stage: SCM ##########\033[0m"
                this.scm()
            }
        }

        steps.stage('Deploy') {
            steps.echo "\033[32m########## Stage: Deploy ##########\033[0m"
            this.deploy()
        }

        steps.stage('Tests') {
            steps.parallel 'API Test': {
                steps.stage('API Test') {
                    if (config.getOrDefault("apiTestEnabled", false)) {
                        steps.echo "\033[32m########## Stage: API Test ##########\033[0m"
                        this.apiTest()
                    }
                }
            }, 'UI Test': {
                steps.stage('UI Test') {
                    if (config.getOrDefault("uiTestEnabled", false)) {
                        steps.echo "\033[32m########## Stage: UI Test ##########\033[0m"
                        this.uiTest()
                    }
                }
            }, 'Smoke Test': {
                if (config.getOrDefault("smokeTestEnabled", false)) {
                    steps.stage('Smoke Test') {
                        steps.echo "\033[32m########## Stage: Smoke Test ##########\033[0m"
                        this.smokeTest()
                    }
                }
            }, failFast: true
        }
    }

    def abstract deploy()

    def abstract apiTest()

    def abstract uiTest()

    def abstract smokeTest()
}