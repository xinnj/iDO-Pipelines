package com.ido.pipeline.image

import com.ido.pipeline.Utils

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
        javaBuilder = javaBuilder.replaceAll('<builderImage>', config.java.builderImage)
        config.podTemplate = javaBuilder

        return super.runBasePipeline(config)
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

        switch (config.java.buildTool) {
            case "maven":
                if (!steps.fileExists("${config.srcRootPath}/mvnw")) {
                    String wrapperFile = steps.libraryResource(resource: 'builder/mvnw.zip', encoding: 'Base64')
                    steps.writeFile(file: "${config.srcRootPath}/mvnw.zip", text: wrapperFile, encoding: 'Base64')
                    steps.unzip(zipFile: "${config.srcRootPath}/mvnw.zip", dir: "${config.srcRootPath}")
                    String wrapperProperties = steps.readFile(file: "${config.srcRootPath}/.mvn/wrapper/maven-wrapper.properties", encoding: "UTF-8")
                    wrapperProperties = wrapperProperties.replaceAll('<maven-version>', config.java.mavenVersion)
                    steps.writeFile(file: "${config.srcRootPath}/.mvn/wrapper/maven-wrapper.properties", text: wrapperProperties, encoding: "UTF-8")
                }

                if (config.java.useDefaultMavenSettings) {
                    String settings = steps.libraryResource(resource: 'builder/default-maven-settings.xml', encoding: 'UTF-8')
                    steps.writeFile(file: "${config.srcRootPath}/default-maven-settings.xml", text: settings, encoding: "UTF-8")
                }

                steps.container('builder') {
                    steps.sh """
                        cd "${config.srcRootPath}"
                        rm -f ./mvnw.zip
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
                    wrapperProperties = wrapperProperties.replaceAll('<gradle-version>', config.java.gradleVersion)
                    steps.writeFile(file: "${config.srcRootPath}/gradle/wrapper/gradle-wrapper.properties", text: wrapperProperties, encoding: "UTF-8")
                }

                if (config.java.useDefaultGradleInitScript) {
                    String initScript = steps.libraryResource(resource: 'builder/default-gradle-init.gradle', encoding: 'UTF-8')
                    steps.writeFile(file: "${config.srcRootPath}/default-gradle-init.gradle", text: initScript, encoding: "UTF-8")
                }

                steps.container('builder') {
                    steps.sh """
                        cd "${config.srcRootPath}"
                        rm -f ./gradlew.zip
                        sh ./gradlew -v
                    """
                }
                break
            default:
                steps.error "java.buildTool: ${config.java.buildTool} is not supported!"
        }
    }

    @Override
    def ut() {
        if (!config.springBoot.utEnabled) {
            return
        }

        steps.container('builder') {
            switch (config.java.buildTool) {
                case "maven":
                    String updateDependenciesArgs = ""
                    if (config.java.forceUpdateDependencies) {
                        updateDependenciesArgs = "-U"
                    }

                    steps.sh """
                        export MAVEN_OPTS="-Xmx2048m -XX:+HeapDumpOnOutOfMemoryError -Dfile.encoding=UTF-8"
                        cd "${config.srcRootPath}"
                        sh ./mvnw org.jacoco:jacoco-maven-plugin:0.8.8:prepare-agent verify org.jacoco:jacoco-maven-plugin:0.8.8:report \
                            -s ./default-maven-settings.xml \
                            "-Dmaven.repo.local=\${MAVEN_USER_HOME}/repository" \
                            ${updateDependenciesArgs} \
                            -Dfile.encoding=UTF-8 \
                            -pl ${config.java.moduleName} -am
                    """
                    break
                case "gradle":
                    Utils.addGradlePlugin(steps, 'jacoco', null, "${config.srcRootPath}/${config.java.moduleName}")

                    String updateDependenciesArgs = ""
                    if (config.java.forceUpdateDependencies) {
                        updateDependenciesArgs = "--refresh-dependencies"
                    }

                    steps.sh """
                        cd "${config.srcRootPath}"
                        mv -f ${config.java.moduleName}/build.gradle ${config.java.moduleName}/build.gradle-original
                        cp -f ${config.java.moduleName}/build.gradle-jacoco ${config.java.moduleName}/build.gradle
                        
                        sh ./gradlew test \
                            --no-daemon \
                            ${updateDependenciesArgs} \
                            -I "${steps.env.WORKSPACE}/${config.srcRootPath}/default-gradle-init.gradle" \
                            -Dfile.encoding=UTF-8 \
                            "-Dorg.gradle.jvmargs=-Xmx2048m -XX:+HeapDumpOnOutOfMemoryError -Dfile.encoding=UTF-8" \
                            -p ${config.java.moduleName}

                        cp -f ${config.java.moduleName}/build.gradle-original ${config.java.moduleName}/build.gradle
                    """
                    break
            }

            steps.jacoco(changeBuildStatus: true, minimumLineCoverage: "${config.springBoot.lineCoverageThreshold}",
                    maximumLineCoverage: "${config.springBoot.lineCoverageThreshold}")
            if (steps.currentBuild.result == 'FAILURE') {
                steps.error "UT coverage failure!"
            }
        }
    }

    @Override
    def codeAnalysis() {
        if (!config.springBoot.codeAnalysisEnabled) {
            return
        }
        steps.container('builder') {
            switch (config.java.buildTool) {
                case "maven":
                    String updateDependenciesArgs = ""
                    if (config.java.forceUpdateDependencies) {
                        updateDependenciesArgs = "-U"
                    }

                    steps.withSonarQubeEnv(config.springBoot.sonarqubeServerName) {
                        steps.sh """
                            export MAVEN_OPTS="-Xmx2048m -XX:+HeapDumpOnOutOfMemoryError -Dfile.encoding=UTF-8"
                            cd "${config.srcRootPath}"
                            sh ./mvnw compile org.sonarsource.scanner.maven:sonar-maven-plugin:3.9.1.2184:sonar \
                                -s ./default-maven-settings.xml \
                                "-Dmaven.repo.local=\${MAVEN_USER_HOME}/repository" \
                                ${updateDependenciesArgs} \
                                -Dfile.encoding=UTF-8 \
                                -pl ${config.java.moduleName} -am
                        """
                    }
                    break
                case "gradle":
                    Utils.addGradlePlugin(steps, 'org.sonarqube', "4.0.0.2929", "${config.srcRootPath}/${config.java.moduleName}")

                    String updateDependenciesArgs = ""
                    if (config.java.forceUpdateDependencies) {
                        updateDependenciesArgs = "--refresh-dependencies"
                    }

                    steps.withSonarQubeEnv(config.springBoot.sonarqubeServerName) {
                        steps.sh """
                            cd "${config.srcRootPath}"
                            mv -f ${config.java.moduleName}/build.gradle ${config.java.moduleName}/build.gradle-original
                            cp -f ${config.java.moduleName}/build.gradle-org.sonarqube ${config.java.moduleName}/build.gradle
                            
                            sh ./gradlew sonar \
                                --no-daemon \
                                ${updateDependenciesArgs} \
                                -I "${steps.env.WORKSPACE}/${config.srcRootPath}/default-gradle-init.gradle" \
                                -Dfile.encoding=UTF-8 \
                                "-Dorg.gradle.jvmargs=-Xmx2048m -XX:+HeapDumpOnOutOfMemoryError -Dfile.encoding=UTF-8" \
                                -p ${config.java.moduleName}
    
                            cp -f ${config.java.moduleName}/build.gradle-original ${config.java.moduleName}/build.gradle
                        """
                    }
                    break
            }

            if (config.springBoot.qualityGateEnabled) {
                steps.timeout(time: config.springBoot.sonarqubeTimeoutMinutes, unit: 'MINUTES') {
                    def qg = steps.waitForQualityGate()
                    if (qg.status != 'OK') {
                        steps.error "Quality gate failure: ${qg.status}"
                    }
                }
            }
        }
    }

    @Override
    def build() {
        steps.container('builder') {
            if (config.registryPull && config.registryPull.credentialsId) {
                steps.withCredentials([steps.usernamePassword(credentialsId: config.registryPull.credentialsId, passwordVariable: 'passwordPull', usernameVariable: 'userNamePull')]) {
                    if (config.registryPush && config.registryPush.credentialsId) {
                        steps.withCredentials([steps.usernamePassword(credentialsId: config.registryPush.credentialsId, passwordVariable: 'passwordPush', usernameVariable: 'userNamePush')]) {
                            runScript()
                        }
                    } else {
                        runScript()
                    }
                }
            } else {
                if (config.registryPush && config.registryPush.credentialsId) {
                    steps.withCredentials([steps.usernamePassword(credentialsId: config.registryPush.credentialsId, passwordVariable: 'passwordPush', usernameVariable: 'userNamePush')]) {
                        runScript()
                    }
                } else {
                    runScript()
                }
            }
        }
    }

    private runScript() {
        if (config._system.imagePullMirror) {
            config.springBoot.baseImage = Utils.replaceImageMirror(config.springBoot.baseImage)
        }

        String jib_from_auth = ""
        if (config.registryPull && config.registryPull.credentialsId) {
            jib_from_auth = "-Djib.from.auth.username=\${userNamePull} -Djib.from.auth.password=\${passwordPull}"
        }

        String jib_to_auth = ""
        if (config.registryPush && config.registryPush.credentialsId) {
            jib_to_auth = "-Djib.to.auth.username=\${userNamePush} -Djib.to.auth.password=\${passwordPush}"
        }

        String main_class = ""
        if (config.springBoot.mainClass) {
            main_class = "-Djib.container.mainClass=" + config.springBoot.mainClass
        }

        String jvm_flags = ""
        if (config.springBoot.jvmFlages) {
            jvm_flags = "\"-Djib.container.jvmFlags=" + config.springBoot.jvmFlages + "\""
        }

        String environment = ""
        if (config.springBoot.environment) {
            environment = "-Djib.container.environment=" + config.springBoot.environment
        }

        switch (config.java.buildTool) {
            case "maven":
                String updateDependenciesArgs = ""
                if (config.java.forceUpdateDependencies) {
                    updateDependenciesArgs = "-U"
                }

                steps.sh """
                        export MAVEN_OPTS="-Xmx2048m -XX:+HeapDumpOnOutOfMemoryError -Dfile.encoding=UTF-8"
                        cd "${config.srcRootPath}"
                        sh ./mvnw compile com.google.cloud.tools:jib-maven-plugin:3.3.2:build \
                            -Dmaven.test.skip=true \
                            ${updateDependenciesArgs} \
                            -s ./default-maven-settings.xml \
                            "-Dmaven.repo.local=\${MAVEN_USER_HOME}/repository" \
                            -Dfile.encoding=UTF-8 \
                            -pl ${config.java.moduleName} -am \
                            -Djib.container.appRoot=${config.springBoot.appRoot} \
                            -Djib.container.workingDirectory=${config.springBoot.appRoot} \
                            -Djib.container.creationTime=USE_CURRENT_TIMESTAMP \
                            -Djib.baseImageCache=/home/jenkins/agent/jib/cache \
                            ${main_class} \
                            ${jvm_flags} \
                            ${environment} \
                            -Djib.allowInsecureRegistries=true \
                            -DsendCredentialsOverHttp=true \
                            ${jib_from_auth} -Djib.from.image=${config.springBoot.baseImage} \
                            ${jib_to_auth} -Djib.to.image=${config.registryPush.url}/${config.productName} -Djib.to.tags=${config.version}
                    """
                break
            case "gradle":
                Utils.addGradlePlugin(steps, 'com.google.cloud.tools.jib', '3.3.2', "${config.srcRootPath}/${config.java.moduleName}")

                String updateDependenciesArgs = ""
                if (config.java.forceUpdateDependencies) {
                    updateDependenciesArgs = "--refresh-dependencies"
                }
                steps.sh """
                        cd "${config.srcRootPath}"
                        mv -f ${config.java.moduleName}/build.gradle ${config.java.moduleName}/build.gradle-original
                        cp -f ${config.java.moduleName}/build.gradle-com.google.cloud.tools.jib ${config.java.moduleName}/build.gradle
                        
                        sh ./gradlew jib \
                            --no-daemon \
                            -x test \
                            ${updateDependenciesArgs} \
                            -I "${steps.env.WORKSPACE}/${config.srcRootPath}/default-gradle-init.gradle" \
                            -Dfile.encoding=UTF-8 \
                            "-Dorg.gradle.jvmargs=-Xmx2048m -XX:+HeapDumpOnOutOfMemoryError -Dfile.encoding=UTF-8" \
                            -p ${config.java.moduleName} \
                            -Djib.container.appRoot=${config.springBoot.appRoot} \
                            -Djib.container.workingDirectory=${config.springBoot.appRoot} \
                            -Djib.container.creationTime=USE_CURRENT_TIMESTAMP \
                            -Djib.baseImageCache=/home/jenkins/agent/jib/cache \
                            ${main_class} \
                            ${jvm_flags} \
                            ${environment} \
                            -Djib.allowInsecureRegistries=true \
                            -DsendCredentialsOverHttp=true \
                            ${jib_from_auth} -Djib.from.image=${config.springBoot.baseImage} \
                            ${jib_to_auth} -Djib.to.image=${config.registryPush.url}/${config.productName} -Djib.to.tags=${config.version}

                        cp -f ${config.java.moduleName}/build.gradle-original ${config.java.moduleName}/build.gradle
                    """
                break
        }
    }
}
