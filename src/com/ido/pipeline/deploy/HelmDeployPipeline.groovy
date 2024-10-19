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
            if (!steps.params.containsKey(varName)) {
                firstRun = true
                break
            }
        }

        List parameters = [steps.text(name: 'USAGE', defaultValue: 'Please provide the chart version of each release.' +
                '\nChart with empty version will be ignored.' +
                "\nFor prod env, version can't be 'Latest Version'.", description: '')]

        ArrayList<String> defaultValue = ['Not Install', 'Latest Version']
        if (config.helm.deploy.env == "prod") {
            defaultValue = ['Not Install']
        }
        for (release in releases) {
            String varName = "CHART_VERSION_" + release.name
            def para = steps.editableChoice(
                    name: varName,
                    choices: defaultValue,
                    defaultValue: 'Not Install',
                    description: '',
                    restrict: false,)
            parameters.add(para)
        }
        steps.properties([
                steps.parameters(parameters)
        ])

        if (firstRun) {
            steps.error "Populating job parameters. Please run the job again."
        }
    }

    @Override
    def deploy() {
        Boolean ociRepo
        String repo, repoServer
        if (config.helm.repo.startsWith('oci://')) {
            ociRepo = true
            repo = config.helm.repo
            repoServer = repo.substring(6)
        } else {
            ociRepo = false
            repo = 'devops'
        }

        steps.container('helm') {
            Closure task = {
                steps.withKubeConfig([credentialsId: config.helm.deploy.kubeconfigCredentialId, restrictKubeConfigAccess: true]) {
                    if (config.helm.deploy.imagePullSecret.credentialId) {
                        def credentials = [steps.usernamePassword(credentialsId: config.helm.deploy.imagePullSecret.credentialId,
                                passwordVariable: 'passwordPull', usernameVariable: 'userNamePull')]
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

                    if (!ociRepo) {
                        steps.sh """${config.debugSh}
                            helm repo add ${repo} ${config.helm.repo}
                            helm repo update ${repo}
                        """
                    } else {
                        if (config.aws.accessKeyCredentialId) {
                            steps.withCredentials([steps.aws(credentialsId: config.aws.accessKeyCredentialId,
                                    accessKeyVariable: 'AWS_ACCESS_KEY_ID', secretKeyVariable: 'AWS_SECRET_ACCESS_KEY')]) {
                                steps.withEnv(["AWS_REGION=${config.aws.region}"]) {
                                    steps.sh """${config.debugSh}
                                        aws ecr get-login-password --region \${AWS_REGION} | \
                                            helm registry login --username AWS --password-stdin ${repoServer}
                                    """
                                }
                            }
                        } else {
                            steps.withCredentials([steps.usernamePassword(credentialsId: config.helm.uploadCredentialId,
                                    passwordVariable: 'passwordPush', usernameVariable: 'userNamePush')]) {
                                steps.sh """${config.debugSh}
                                    helm registry login -u \${userNamePush} -p \${passwordPush} ${repoServer}
                                """
                            }
                        }
                    }

                    def releases = config.helm.deploy.releases as List
                    def versions = [:]

                    releases.each {
                        String releaseName = (it as Map).name

                        if (!it.chart) {
                            it['chart'] = releaseName
                        }

                        String varName = "CHART_VERSION_" + releaseName
                        String chartVersion = (steps.params[varName] as String).trim()

                        if (chartVersion == "Latest Version") {
                            if (config.helm.deploy.env == "prod") {
                                steps.error "Chart version can't be 'Latest Version' for prod env."
                            }
                            chartVersion = steps.sh(returnStdout: true, script: """${config.debugSh}
helm show chart ${repo}/${it.chart} | grep ^version: | awk '{printf \$2}'
""")
                        }

                        if (chartVersion && chartVersion != 'Not Install') {
                            versions[it.chart] = chartVersion
                        }
                    }

                    if (versions.size() == 0) {
                        steps.error "All chart versions are empty."
                    } else {
                        String buildDescription = ""
                        versions.each {
                            buildDescription = buildDescription + it.key + ": " + it.value + "<p>"
                        }
                        steps.currentBuild.description = buildDescription
                    }

                    def parallelRuns = [:]
                    for (int i = 0; i < releases.size(); i++) {
                        int j = i
                        if (versions[(releases.get(j) as Map).chart]) {
                            parallelRuns["Deploy: " + (releases.get(j) as Map).name] = {
                                String releaseName = (releases.get(j) as Map).name

                                String chartName = (releases.get(j) as Map).chart
                                String chartVersion = versions[chartName]
                                String versionParam = " --version " + chartVersion

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
                                helm upgrade ${releaseName} ${repo}/${chartName} --install --wait \
                                    --namespace ${config.helm.deploy.namespace} --create-namespace \
                                    ${versionParam} ${valuesParam} ${setParam}
                            """

                                steps.echo "Successfully deploy: ${releaseName} ${chartName} ${chartVersion}"
                            }
                        }
                    }

                    steps.parallel parallelRuns
                }
            }

            if (config.aws.accessKeyCredentialId) {
                steps.withCredentials([steps.aws(credentialsId: config.aws.accessKeyCredentialId,
                        accessKeyVariable: 'AWS_ACCESS_KEY_ID', secretKeyVariable: 'AWS_SECRET_ACCESS_KEY')]) {
                    steps.withEnv(["AWS_REGION=${config.aws.region}"]) {
                        task()
                    }
                }
            } else {
                task()
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
