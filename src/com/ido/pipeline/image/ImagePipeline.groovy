package com.ido.pipeline.image

import com.ido.pipeline.base.BasePipeline

/**
 * @author xinnj
 */
abstract class ImagePipeline extends BasePipeline {
    ImagePipeline(steps) {
        super(steps)
    }

    @Override
    Map runPipeline(Map config) {
        def result = super.runPipeline(config)
        result.put("imageTag", config.version)
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

        steps.stage('Versioning') {
            steps.echo "########## Stage: Versioning ##########"
            this.versioning(config)
        }

        steps.stage('UT') {
            steps.echo "########## Stage: UT ##########"
            this.ut(config)
        }

        steps.stage('Code Analysis') {
            steps.echo "########## Stage: Code Analysis ##########"
            this.codeAnalysis(config)
        }

        steps.stage('Build') {
            steps.echo "########## Stage: Build ##########"
            this.build(config)
        }
    }

    def abstract ut(Map config)

    def abstract codeAnalysis(Map config)

    @Override
    def versioning(Map config) {
        super.versioning(config)

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