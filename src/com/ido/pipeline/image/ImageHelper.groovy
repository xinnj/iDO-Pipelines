package com.ido.pipeline.image

import com.ido.pipeline.Utils
import com.ido.pipeline.base.HelmChart

/**
 * @author xinnj
 */
public class ImageHelper {
    def steps
    Map config

    ImageHelper(Object steps, Map config) {
        this.steps = steps
        this.config = config
    }

    def buildImage() {
        if (!steps.fileExists("${config.srcRootPath}/.dockerignore")) {
            String dockerignore = steps.libraryResource(resource: 'builder/default-dockerignore', encoding: 'UTF-8')
            steps.writeFile(file: "${config.srcRootPath}/.dockerignore", text: dockerignore, encoding: 'UTF-8')
        }
        if (!config.dockerFile) {
            if (config._system.imagePullMirror) {
                config.defaultDockerfile = Utils.replaceImageMirror(config._system.imageMirrors, config.defaultDockerfile)
            }

            steps.writeFile(file: "${config.srcRootPath}/Dockerfile", text: config.defaultDockerfile, encoding: 'UTF-8')
            config.dockerFile = "Dockerfile"
        }

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

    private runBuildah() {
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

    def buildHelm() {
        if (config.helm.buildChart && config.helm.chartPath) {
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

            Map values = steps.readYaml(file: "${config.helm.chartPath}/values.yaml")
            values.image.tag = config.version
            steps.writeYaml(file: "${config.helm.chartPath}/values.yaml", data: values, charset: "UTF-8", overwrite: true)

            steps.container('helm') {
                steps.sh """
                helm package --version ${config.helm.chartVersion} --app-version ${config.version} ${config.helm.chartPath}
            """
            }

            new HelmChart().upload(steps, config)
        }
    }
}