package com.ido.pipeline.deploy

import com.ido.pipeline.base.DeployPipeline

/**
 * @author xinnj
 */
class HelmDeployPipeline extends DeployPipeline {
    HelmDeployPipeline(Object steps) {
        super(steps)
    }

    @Override
    Map runPipeline(Map config) {
        if (!config.podTemplate) {
            String builder = steps.libraryResource(resource: 'pod-template/helm-deployer.yaml', encoding: 'UTF-8')
            config.podTemplate = builder
        }

        return super.runPipeline(config)
    }

    @Override
    def prepare() {
        super.prepare()

        if (!config.helm.deploy.env) {
            steps.error "helm.deploy.env is empty!"
        }
        if (!config.helm.deploy.kubeconfigCredentialId) {
            steps.error "helm.deploy.kubeconfigCredentialId is empty!"
        }
        if (!config.helm.deploy.namespace) {
            steps.error "helm.deploy.namespace is empty!"
        }
        if (!config.helm.deploy.releases) {
            steps.error "helm.deploy.releases is empty!"
        }

        if (config.helm.deploy.env == "dev") {
            config.deployCheckoutSCM = false
        } else {
            config.deployCheckoutSCM = true
        }

        def releases = config.helm.deploy.releases as List
        Boolean firstRun = false
        for (release in releases) {
            String varName = "CHART_VERSION_" + release.name
            if (!steps.params[varName]) {
                firstRun = true
                break
            }
        }

        List parameters = [steps.text(name: 'USAGE', defaultValue: 'Please provide the chart version of each release.', description: '')]
        for (release in releases) {
            String varName = "CHART_VERSION_" + release.name
            def para = steps.string(name: varName, defaultValue: 'latest', description: '', trim: true)
            parameters.add(para)
        }
        steps.properties([
                steps.parameters(parameters)
        ])

        if (firstRun) {
            steps.error "Populating job parameters. Please run the job again."
        }

        for (release in releases) {
            String varName = "CHART_VERSION_" + release.name
            if (!steps.params[varName]) {
                steps.error "Chart version can't be empty."
            }
        }

    }

    @Override
    def deploy() {
        steps.container('helm') {
            steps.withKubeConfig([credentialsId: config.helm.deploy.kubeconfigCredentialId, restrictKubeConfigAccess: true]) {
                if (config.helm.deploy.imagePullSecret.credentialId) {
                    def credentials = [steps.usernamePassword(credentialsId: config.helm.deploy.imagePullSecret.credentialId,
                            passwordVariable: 'passwordPull', usernameVariable: 'userNamePull')]
                    if (config.helm.deploy.awsEksCredentialId) {
                        credentials.add(steps.aws(credentialsId: config.helm.deploy.awsEksCredentialId,
                                accessKeyVariable: 'AWS_ACCESS_KEY_ID', secretKeyVariable: 'AWS_SECRET_ACCESS_KEY'))
                    }
                    steps.withCredentials(credentials) {
                        steps.sh """${config.debugSh}
                            kubectl create namespace ${config.helm.deploy.namespace} --dry-run=client -o yaml | kubectl apply -f -
                            kubectl delete -n ${config.helm.deploy.namespace} secret ${config.helm.deploy.imagePullSecret.name} --ignore-not-found=true
                            kubectl create -n ${config.helm.deploy.namespace} secret docker-registry ${config.helm.deploy.imagePullSecret.name} \
                            --docker-server=${config.helm.deploy.imagePullSecret.registry} \
                            --docker-username=\${userNamePull} --docker-password=\${passwordPull}
                        """
                    }
                }

                steps.sh """${config.debugSh}
                    helm repo add devops ${config.helm.repo}
                    helm repo update devops
                """

                def releases = config.helm.deploy.releases as List

                def parallelRuns = [:]
                for (int i = 0; i < releases.size(); i++) {
                    int j = i
                    parallelRuns["Deploy: " + (releases.get(j) as Map).name] = {
                        String releaseName = (releases.get(j) as Map).name

                        String chartName = (releases.get(j) as Map).chart
                        if (!chartName) {
                            chartName = releaseName
                        }

                        String varName = "CHART_VERSION_" + releaseName
                        String chartVersion = steps.params[varName]

                        String versionParam = ""
                        if (chartVersion != "latest") {
                            versionParam = " --version " + chartVersion
                        }

                        String valuesParam = ""
                        if (config.helm.deploy.env != "dev") {
                            String valueFile = (releases.get(j) as Map).valueFile
                            if (valueFile) {
                                valuesParam = "--values ${valueFile}"
                            } else {
                                valuesParam = "--values ${releaseName}/values-${config.helm.deploy.env}.yaml"
                            }
                        }

                        String setParam = ""
                        String valueSet = (releases.get(j) as Map).valueSet
                        if (valueSet) {
                            setParam = "--set ${valueSet}"
                        }

                        steps.sh """${config.debugSh}
                            helm upgrade ${releaseName} devops/${chartName} --install --wait \
                                --namespace ${config.helm.deploy.namespace} --create-namespace \
                                ${versionParam} ${valuesParam} ${setParam}
                        """

                        steps.echo "Successfully deploy: ${releaseName} ${chartName} ${chartVersion}"
                    }
                }
                steps.echo "Execute parallel"
                if (config.helm.deploy.awsEksCredentialId) {
                    steps.withCredentials([steps.aws(credentialsId: config.helm.deploy.awsEksCredentialId,
                            accessKeyVariable: 'AWS_ACCESS_KEY_ID', secretKeyVariable: 'AWS_SECRET_ACCESS_KEY')]) {
                        steps.parallel parallelRuns

                        steps.sh """${config.debugSh}
                            helm list
                        """
                    }
                } else {
                    steps.parallel parallelRuns

                    steps.sh """${config.debugSh}
                        helm list
                    """
                }
            }
        }
    }

    @Override
    def apiTest() {}

    @Override
    def uiTest() {}

    @Override
    def smokeTest() {}
}
