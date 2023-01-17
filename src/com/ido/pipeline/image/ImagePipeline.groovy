package com.ido.pipeline.image

import com.ido.pipeline.base.BasePipeline
import com.ido.pipeline.base.HelmChart

/**
 * @author xinnj
 */
abstract class ImagePipeline extends BasePipeline {
    ImagePipeline(Object steps) {
        super(steps)
    }

    @Override
    Map runPipeline(Map config) {
        config.podTemplate = (config.podTemplate as String)
                .replaceAll('<keepBuilderPodMinutes>', (config.keepBuilderPodMinutes).toString())
                .replaceAll('<imagePullSecret>', config._system.imagePullSecret as String)

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

        steps.stage('Build Image') {
            steps.echo "########## Stage: Build Image ##########"
            this.build()
        }

        steps.stage('Build Helm Chart') {
            if (config.helm.buildChart && config.helm.chartPath) {
                steps.echo "########## Stage: Build Helm Chart ##########"
                this.buildHelmChart()
            }
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

        if (!config.helm.chartVersion) {
            String[] strArr = config.version.split('\\.')

            String chartVersion
            if (strArr.size() >= 3) {
                chartVersion = strArr[0] + '.' + strArr[1] + '.' + strArr[2]
            } else {
                chartVersion = strArr.join('.')
                for (int i = 1; i <= (3 - strArr.size()); i++) {
                    chartVersion = chartVersion + '.0'
                }
            }
            config.helm.put("chartVersion", chartVersion)
        }
    }

    def buildHelmChart() {
        Map values = steps.readYaml(file: "${config.helm.chartPath}/values.yaml")
        values.image.tag = config.version
        steps.writeYaml(file: "${config.helm.chartPath}/values.yaml", data: values, charset: "UTF-8", overwrite: true)

        steps.container('helm') {
            steps.sh """
                helm package --version ${config.helm.chartVersion} --app-version ${config.version} ${config.helm.chartPath}
            """
        }

        HelmChart helmChart = new HelmChart()
        helmChart.upload(steps, config)
    }
}