package com.ido.pipeline.image

import com.ido.pipeline.base.HelmChart

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
        String javaBuilder = steps.libraryResource(resource: 'pod-template/java-builder.yaml', encoding: 'UTF-8')
        javaBuilder = javaBuilder.replaceAll('<jdk-version>', config.jdkVersion)
        config.podTemplate = javaBuilder

        return super.runPipeline(config)
    }

    @Override
    def prepare() {
        super.prepare()

        if (!config.springBoot.baseImage) {
            steps.error "springBoot.baseImage is empty!"
        }
    }

    @Override
    def scm() {
        super.scm()

        switch (config.javaBuildTool) {
            case "maven":
                if (!steps.fileExists("${config.srcRootPath}/mvnw")) {
                    String wrapperFile = steps.libraryResource(resource: 'builder/mvnw.zip', encoding: 'Base64')
                    steps.writeFile(file: "${config.srcRootPath}/mvnw.zip", text: wrapperFile, encoding: 'Base64')
                    steps.unzip(zipFile: "${config.srcRootPath}/mvnw.zip", dir: "${config.srcRootPath}")
                    String wrapperProperties = steps.readFile(file: "${config.srcRootPath}/.mvn/wrapper/maven-wrapper.properties", encoding: "UTF-8")
                    wrapperProperties = wrapperProperties.replaceAll('<maven-version>', config.mavenVersion)
                    steps.writeFile(file: "${config.srcRootPath}/.mvn/wrapper/maven-wrapper.properties", text: wrapperProperties, encoding: "UTF-8")
                }

                String settings = steps.libraryResource(resource: 'builder/default-maven-settings.xml', encoding: 'UTF-8')
                steps.writeFile(file: "${config.srcRootPath}/default-maven-settings.xml", text: settings, encoding: "UTF-8")

                steps.container('builder') {
                    steps.sh """
                        cd "${config.srcRootPath}"
                        sh ./mvnw -v
                    """
                }
                break
            case "gradle":
                if (!steps.fileExists("${config.srcRootPath}/gradlew")) {
                    String wrapperFile = steps.libraryResource(resource: 'builder/gradlew.zip', encoding: 'Base64')
                    steps.writeFile(file: "${config.srcRootPath}/gradlew.zip", text: wrapperFile, encoding: 'Base64')
                    steps.unzip(zipFile: "${config.srcRootPath}/gradlew.zip", dir: "${config.srcRootPath}")
                    String wrapperProperties = steps.readFile(file: "${config.srcRootPath}/gradle/wrapper/gradle-wrapper.properties", encoding: "UTF-8")
                    wrapperProperties = wrapperProperties.replaceAll('<gradle-version>', config.gradleVersion)
                    steps.writeFile(file: "${config.srcRootPath}/gradle/wrapper/gradle-wrapper.properties", text: wrapperProperties, encoding: "UTF-8")
                }

                String initScript = steps.libraryResource(resource: 'builder/default-gradle-init.gradle', encoding: 'UTF-8')
                steps.writeFile(file: "${config.srcRootPath}/default-gradle-init.gradle", text: initScript, encoding: "UTF-8")

                steps.container('builder') {
                    steps.sh """
                        cd "${config.srcRootPath}"
                        sh ./gradlew -v
                    """
                }
                break
            default:
                steps.error "javaBuildTool: ${config.javaBuildTool} is not supported!"
        }
    }

    @Override
    def ut() {
        // todo:
        return
        steps.container('builder') {
            steps.sh """
                mvn clean org.jacoco:jacoco-maven-plugin:0.8.8:prepare-agent install org.jacoco:jacoco-maven-plugin:0.8.8:report \
                    -U -Dmaven.test.skip=false
            """
            steps.jacoco()
        }
    }

    @Override
    def codeAnalysis() {
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
    def build() {
        def registryPull = config.registryPull as Map
        def jib_from_auth = ""
        if (registryPull != null) {
            if (registryPull.credentialsId) {
                steps.withCredentials([steps.usernamePassword(credentialsId: registryPull.credentialsId, passwordVariable: 'passwordPull', usernameVariable: 'userNamePull')]) {
                    jib_from_auth = "-Djib.from.auth.username=" + steps.env.userNamePull + " -Djib.from.auth.password=" + steps.env.passwordPull
                }
            }
        }

        def registryPush = config.registryPush as Map
        def jib_to_auth = ""
        if (registryPush != null) {
            if (registryPush.credentialsId) {
                steps.withCredentials([steps.usernamePassword(credentialsId: registryPush.credentialsId, passwordVariable: 'passwordPush', usernameVariable: 'userNamePush')]) {
                    jib_to_auth = "-Djib.to.auth.username=" + steps.env.userNamePush + " -Djib.to.auth.password=" + steps.env.passwordPush
                }
            }
        }

        def main_class = ""
        if (config.springBoot.mainClass) {
            main_class = "-Djib.container.mainClass=" + config.springBoot.mainClass
        }

        def jvm_flags = ""
        if (config.springBoot.jvmFlages) {
            jvm_flags = "\"-Djib.container.jvmFlags=" + config.springBoot.jvmFlages + "\""
        }

        def environment = ""
        if (config.springBoot.environment) {
            environment = "-Djib.container.environment=" + config.springBoot.environment
        }

        steps.container('builder') {
            switch (config.javaBuildTool) {
                case "maven":
                    String updateDependenciesArgs = ""
                    if (config.javaUpdateDependencies) {
                        updateDependenciesArgs = "-U"
                    }

                    steps.sh """
                        export MAVEN_OPTS="-Xmx2048m -XX:MaxPermSize=512m -XX:+HeapDumpOnOutOfMemoryError -Dfile.encoding=UTF-8"
                        cd "${config.srcRootPath}"
                        sh ./mvnw clean compile com.google.cloud.tools:jib-maven-plugin:3.3.1:build \
                            -Dmaven.test.skip=true \
                            ${updateDependenciesArgs} \
                            -s ./default-maven-settings.xml \
                            "-Dmaven.repo.local=\${MAVEN_USER_HOME}/repository" \
                            -Dfile.encoding=UTF-8 \
                            -pl ${config.javaModuleName} -am \
                            -Djib.container.appRoot=${config.springBoot.appRoot} \
                            -Djib.container.workingDirectory=${config.springBoot.appRoot} \
                            -Djib.container.creationTime=USE_CURRENT_TIMESTAMP \
                            -Djib.baseImageCache=/home/jenkins/agent/jib/cache \
                            ${main_class} \
                            ${jvm_flags} \
                            ${environment} \
                            -Djib.allowInsecureRegistries=true \
                            ${jib_from_auth} -Djib.from.image=${config.springBoot.baseImage} \
                            ${jib_to_auth} -Djib.to.image=${registryPush.url}/${config.imageName} -Djib.to.tags=${config.version}
                    """
                    break
                case "gradle":
                    addPlugin('com.google.cloud.tools.jib', '3.3.1', "${config.srcRootPath}/build.gradle")

                    String updateDependenciesArgs = ""
                    if (config.javaUpdateDependencies) {
                        updateDependenciesArgs = "--refresh-dependencies"
                    }
                    steps.sh """
                        cd "${config.srcRootPath}"
                        mv -f ./build.gradle ./build.gradle-original
                        cp -f ./build.gradle-com.google.cloud.tools.jib ./build.gradle
                        
                        sh ./gradlew clean jib \
                            --no-daemon \
                            -x test \
                            ${updateDependenciesArgs} \
                            -I ./default-gradle-init.gradle \
                            -Dfile.encoding=UTF-8 \
                            "-Dorg.gradle.jvmargs=-Xmx2048m -XX:MaxPermSize=512m -XX:+HeapDumpOnOutOfMemoryError -Dfile.encoding=UTF-8" \
                            -p ${config.javaModuleName} \
                            -Djib.container.appRoot=${config.springBoot.appRoot} \
                            -Djib.container.workingDirectory=${config.springBoot.appRoot} \
                            -Djib.container.creationTime=USE_CURRENT_TIMESTAMP \
                            -Djib.baseImageCache=/home/jenkins/agent/jib/cache \
                            ${main_class} \
                            ${jvm_flags} \
                            ${environment} \
                            -Djib.allowInsecureRegistries=true \
                            ${jib_from_auth} -Djib.from.image=${config.springBoot.baseImage} \
                            ${jib_to_auth} -Djib.to.image=${registryPush.url}/${config.imageName} -Djib.to.tags=${config.version}

                        cp -f ./build.gradle-original ./build.gradle
                    """
                    break
            }

            if (config.helm.buildChart && config.helm.chartPath) {
                Map values = steps.readYaml(file: "${config.helm.chartPath)}/values.yaml")
                values.image.tag = config.version
                steps.writeYaml(file: "${config.helm.chartPath)}/values.yaml", data: values, charset: "UTF-8", overwrite: true)

                steps.container('helm') {
                    steps.sh """
                        helm package --version ${config.helm.chartVersion} --app-version ${config.version} ${config.helm.chartPath}
                    """
                }

                HelmChart helmChart = new HelmChart()
                helmChart.upload(steps, config)
            }
        }
    }

    private addPlugin(String pluginId, String pluginVersion, String buildGradlePath) {
        String buildGradle = steps.readFile(file: buildGradlePath, encoding: "UTF-8")
        def m = buildGradle =~ /(?is)${pluginId}/;
        if (!m) {
            String plugins = ""
            m = buildGradle =~ /(?is)(plugins\s*?\{.*?)\}/;
            if (m) {
                plugins = m[0][1] + "\tid \"${pluginId}\" version \"${pluginVersion}\"\n}"
            } else {
                plugins = """
plugins {
  id "${pluginId}" version "${pluginVersion}"
}
"""
            }
            m = null

            buildGradle = buildGradle.replaceAll("(?is)plugins\\s*?\\{.*?\\}", plugins)
            steps.writeFile(file: "${buildGradlePath}-${pluginId}", text: buildGradle, encoding: 'UTF-8')
        }
    }
}
