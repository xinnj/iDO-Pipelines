package com.ido.pipeline.image

import com.ido.pipeline.base.BasePipeline

/**
 * @author xinnj
 */
abstract class ImagePipeline extends BasePipeline {
    ImagePipeline(Object steps) {
        super(steps)
    }

    @Override
    Map runPipeline(Map config) {
        def result = super.runPipeline(config)
        result.put("imageTag", config.version)
        return result
    }

    @Override
    def customStages() {
        steps.stage('Prepare') {
            steps.echo "########## Stage: Prepare ##########"
            this.prepare()
        }

        steps.stage('SCM') {
            steps.echo "########## Stage: SCM ##########"
            this.scm()
        }

        steps.stage('Versioning') {
            steps.echo "########## Stage: Versioning ##########"
            this.versioning()
        }

        steps.stage('UT') {
            steps.echo "########## Stage: UT ##########"
            this.ut()
        }

        steps.stage('Code Analysis') {
            steps.echo "########## Stage: Code Analysis ##########"
            this.codeAnalysis()
        }

        steps.stage('Build') {
            steps.echo "########## Stage: Build ##########"
            this.build()
        }
    }

    @Override
    def prepare() {
        super.prepare()

        if (!config.imageName) {
            steps.error "imageName is empty!"
        }
    }

    def abstract ut()

    def abstract codeAnalysis()

    @Override
    def versioning() {
        super.versioning()

        if (!config.chartVersion) {
            String[] strArr = config.version.split('\\.')

            String chartVersion
            if (strArr.size() >= 3) {
                chartVersion = strArr[0] + '.' + strArr[1] + '.' + strArr[2]
            } else {
                chartVersion = strArr.join('.')
            }
            config.put("chartVersion", chartVersion)
        }
    }
}