package com.ido.pipeline

import com.ido.pipeline.base.BasePipeline

/**
 * @author xinnj
 */
class GenericPipeline extends BasePipeline {
    GenericPipeline(steps) {
        super(steps)
    }

    @Override
    final Map runPipeline(Map config) {
        def result = super.runPipeline(config)
        return result
    }

    @Override
    def customStages(Map config) {
        steps.stage('Prepare') {
            steps.echo "########## Stage: Prepare ##########"
            this.prepare(config)
        }

        steps.stage('SCM') {
            steps.echo "########## Stage: SCM ##########"
            this.scm(config)
        }

        steps.stage('Build') {
            steps.echo "########## Stage: Build ##########"
            this.build(config)
        }
    }

    def prepare(Map config) {}

    def build(Map config) {
        if (steps.isUnix()) {
            steps.sh '''
                echo "hello world"
            '''
        } else {
            steps.powershell '''
                echo "hello world"
            '''
        }
    }
}