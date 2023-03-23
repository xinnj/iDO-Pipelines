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

        if (config.parallelUtAnalysis) {
            steps.parallel 'UT': {
                steps.stage('UT') {
                    steps.echo "########## Stage: UT ##########"
                    this.ut()
                }
            }, 'Code Analysis': {
                steps.stage('Code Analysis') {
                    steps.echo "########## Stage: Code Analysis ##########"
                    this.codeAnalysis()
                }
            }, failFast: true
        } else {
            steps.stage('UT') {
                steps.echo "########## Stage: UT ##########"
                this.ut()
            }

            steps.stage('Code Analysis') {
                steps.echo "########## Stage: Code Analysis ##########"
                this.codeAnalysis()
            }
        }

        steps.parallel 'Build Image': {
            steps.stage('Build Image') {
                steps.echo "########## Stage: Build Image ##########"
                this.build()
            }
        }, 'Build Helm Chart': {
            steps.stage('Build Helm Chart') {
                if (config.helm.buildChart && config.helm.chartPath) {
                    steps.echo "########## Stage: Build Helm Chart ##########"
                    this.buildHelmChart()
                }
            }
        }, failFast: true
    }

    @Override
    def prepare() {
        super.prepare()
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

    def buildImage() {
        steps.container('buildah') {
            if (config.registryPull && config.registryPull.credentialsId) {
                steps.withCredentials([steps.usernamePassword(credentialsId: config.registryPull.credentialsId, passwordVariable: 'passwordPull', usernameVariable: 'userNamePull')]) {
                    if (config.registryPush && config.registryPush.credentialsId) {
                        steps.withCredentials([steps.usernamePassword(credentialsId: config.registryPush.credentialsId, passwordVariable: 'passwordPush', usernameVariable: 'userNamePush')]) {
                            this.runBuildah()
                        }
                    } else {
                        this.runBuildah()
                    }
                }
            } else {
                if (config.registryPush && config.registryPush.credentialsId) {
                    steps.withCredentials([steps.usernamePassword(credentialsId: config.registryPush.credentialsId, passwordVariable: 'passwordPush', usernameVariable: 'userNamePush')]) {
                        this.runBuildah()
                    }
                } else {
                    this.runBuildah()
                }
            }
        }
    }

    def runBuildah() {
        String registry_login_pull = ""
        if (config.registryPull && config.registryPull.credentialsId) {
            registry_login_pull = "buildah login --tls-verify=false -u \${userNamePull}  -p \${passwordPull} " + config.registryPull.url
        }

        String registry_login_push = ""
        if (config.registryPush && config.registryPush.credentialsId) {
            registry_login_push = "buildah login --tls-verify=false -u \${userNamePush}  -p \${passwordPush} " + config.registryPush.url
        }

        String pushImageFullName = "${config.registryPush.url}/${config.productName}:${config.version}"
        steps.sh """
            cd "${config.srcRootPath}"
            alias buildah="buildah --root /var/buildah-cache --runroot /tmp/containers"
            pushImageFullName=${config.registryPush.url}/${config.productName}:${config.version}
            ${registry_login_pull}
            buildah build --format ${config._system.imageFormat} -f ${config.dockerFile} -t ${pushImageFullName} ./
            
            ${registry_login_push}
            buildah push --tls-verify=false ${pushImageFullName}
            buildah rmi ${pushImageFullName}
        """
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