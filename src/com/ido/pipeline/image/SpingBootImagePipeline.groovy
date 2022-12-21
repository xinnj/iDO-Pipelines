package com.ido.pipeline.image

/**
 * @author xinnj
 */
class SpringBootImagePipeline extends ImagePipeline {
    SpringBootImagePipeline(Object steps) {
        super(steps)
    }

    @Override
    Map runPipeline(Map config) {
        config.nodeType = "k8s"
        Map podTemplate = steps.readYaml(text: steps.libraryResource('pod-template/maven.yaml'))
        config.podTemplate = steps.writeYaml(returnText: true, data: podTemplate)

        return super.runPipeline(config)
    }

    @Override
    def ut(Map config) {
        // todo:
        return
        steps.container('maven') {
            steps.sh """
                mvn clean org.jacoco:jacoco-maven-plugin:0.8.8:prepare-agent install org.jacoco:jacoco-maven-plugin:0.8.8:report \
                    -U -Dmaven.test.skip=false
            """
            steps.jacoco()
        }
    }

    @Override
    def codeAnalysis(Map config) {
        // todo:
        return
        steps.withSonarQubeEnv() {
            steps.sh 'mvn org.sonarsource.scanner.maven:sonar-maven-plugin:3.7.0.1746:sonar'
        }

        if (config.enableSonarQualityGate as Boolean) {
            steps.timeout(time: 10, unit: 'MINUTES') {
                def qg = steps.waitForQualityGate()
                if (qg.status != 'OK') {
                    steps.error "Pipeline aborted due to quality gate failure: ${qg.status}"
                }
            }
        }
    }

    @Override
    def build(Map config) {
        if (config.applicationName == null || config.applicationName == "") {
            steps.error "applicationName is null！"
        }

        def registryPull = config.registryPull as Map
        def jib_from_auth = ""
        if (registryPull != null) {
            if (registryPull.credentialsId != null && registryPull.credentialsId != "") {
                steps.withCredentials([steps.usernamePassword(credentialsId: registryPull.credentialsId, passwordVariable: 'passwordPull', usernameVariable: 'userNamePull')]) {
                    jib_from_auth = "-Djib.from.auth.username=" + steps.env.userNamePull + " -Djib.from.auth.password=" + steps.env.passwordPull
                }
            }
        }

        def registryPush = config.registryPush as Map
        def jib_to_auth = ""
        if (registryPush != null) {
            if (registryPush.credentialsId != null && registryPush.credentialsId != "") {
                steps.withCredentials([steps.usernamePassword(credentialsId: registryPush.credentialsId, passwordVariable: 'passwordPush', usernameVariable: 'userNamePush')]) {
                    jib_to_auth = "-Djib.to.auth.username=" + steps.env.userNamePush + " -Djib.to.auth.password=" + steps.env.passwordPush
                }
            }
        }

        def main_class = ""
        if (config.mainClass != null && config.mainClass != "") {
            main_class = "-Djib.container.mainClass=" + config.mainClass
        }

        steps.sh label: '', script: """
            mkdir -p ${config.module_name}/src/main/jib
            cp -rf k8s ${config.module_name}/src/main/jib
            rm -f ${config.module_name}/src/main/resources/application.yml
            mvn clean compile com.google.cloud.tools:jib-maven-plugin:2.7.1:build -f ${config.module_name}/pom.xml ${main_class} \
                -Djib.allowInsecureRegistries=true -Djib.container.appRoot=${config.appRoot} -Djib.container.workingDirectory=${config.appRoot} \
                ${jib_from_auth} -Djib.from.image=${config.jdkBaseImage} \
                "-Djib.container.jvmFlags=-Duser.timezone=PRC -javaagent:/skywalking-agent/skywalking-agent.jar" \
                -Djib.container.creationTime=USE_CURRENT_TIMESTAMP -Djib.container.environment=TZ='Asia/Shanghai' \
                ${jib_to_auth} -Djib.to.image=${registryPush.host}/${config.applicationName} -Djib.to.tags=${config.imageTag}
            rm -rf ${config.module_name}/src/main/jib
        """
    }
}
