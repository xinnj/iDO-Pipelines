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
            steps.echo "########## Stage: Prepare ##########"
            this.prepare()
        }

        if (config.deployCheckoutSCM) {
            steps.stage('SCM') {
                steps.echo "########## Stage: SCM ##########"
                this.scm()
            }
        }

        steps.stage('Deploy') {
            steps.echo "########## Stage: Deploy ##########"
            this.deploy()
        }

        steps.stage('Tests') {
            steps.parallel 'API Test': {
                steps.stage('API Test') {
                    if (config.getOrDefault("apiTestEnabled", false)) {
                        steps.echo "########## Stage: API Test ##########"
                        this.apiTest()
                    }
                }
            }, 'UI Test': {
                steps.stage('UI Test') {
                    if (config.getOrDefault("uiTestEnabled", false)) {
                        steps.echo "########## Stage: UI Test ##########"
                        this.uiTest()
                    }
                }
            }, 'Smoke Test': {
                if (config.getOrDefault("smokeTestEnabled", false)) {
                    steps.stage('Smoke Test') {
                        steps.echo "########## Stage: Smoke Test ##########"
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