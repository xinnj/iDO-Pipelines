package com.ido.pipeline.base

class HelmChart {
    def upload(Object steps, Map config) {
        Map chart = steps.readYaml(file: "${config.helm.chartPath}/Chart.yaml")
        String chartName = chart.name

        steps.withCredentials([steps.usernameColonPassword(credentialsId: config.helm.uploadCredentialId, variable: 'USERPASS')]) {
            steps.container('helm') {
                steps.sh """
                    curl -u "\${USERPASS}" "${config.helm.repo}" -v --upload-file "${chartName}-${config.helm.chartVersion}.tgz"
                """
            }
        }
    }
}
