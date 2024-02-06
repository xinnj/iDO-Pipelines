package com.ido.pipeline.languageBase

import com.ido.pipeline.Utils
import com.ido.pipeline.base.BasePipeline

/**
 * @author xinnj
 */
abstract class JdkPipeline extends BasePipeline {
    JdkPipeline(Object steps) {
        super(steps)
    }

    @Override
    Map runPipeline(Map config) {
        config.parallelUtAnalysis = false

        if (!config.podTemplate) {
            String builder = steps.libraryResource(resource: 'pod-template/jdk-builder.yaml', encoding: 'UTF-8')
            builder = builder.replaceAll('<builderImage>', config.java.builderImage)
            config.podTemplate = builder
        }

        return super.runPipeline(config)
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
                    wrapperProperties = wrapperProperties
                            .replaceAll('<maven-download-url>', config._system.java.mavenDownloadUrl)
                            .replaceAll('<maven-version>', config.java.mavenVersion)
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
                    wrapperProperties = wrapperProperties
                            .replaceAll('<gradle-download-url>', config._system.java.gradleDownloadUrl)
                            .replaceAll('<gradle-version>', config.java.gradleVersion)
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
        if (!config.context.utEnabled) {
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
                        sh ./mvnw org.jacoco:jacoco-maven-plugin:0.8.11:prepare-agent verify org.jacoco:jacoco-maven-plugin:0.8.11:report \
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
                        cat <<EOF >> ${config.java.moduleName}/build.gradle-jacoco
jacocoTestReport {
   dependsOn test
   reports {
       xml.required = true
       xml.destination = file("build/jacoco.xml")
       html.required = false
   }
}
EOF
                        mv -f ${config.java.moduleName}/build.gradle ${config.java.moduleName}/build.gradle-original
                        cp -f ${config.java.moduleName}/build.gradle-jacoco ${config.java.moduleName}/build.gradle
                        
                        sh ./gradlew jacocoTestReport \
                            --no-daemon \
                            ${updateDependenciesArgs} \
                            -I "${steps.env.WORKSPACE}/${config.srcRootPath}/default-gradle-init.gradle" \
                            -Dfile.encoding=UTF-8 \
                            "-Dorg.gradle.jvmargs=-Xmx2048m -Dfile.encoding=UTF-8" \
                            -p ${config.java.moduleName}

                        cp -f ${config.java.moduleName}/build.gradle-original ${config.java.moduleName}/build.gradle
                    """
                    break
            }

            steps.recordCoverage(tools: [[parser: 'JACOCO']], failOnError: true, sourceCodeRetention: 'NEVER',
                    qualityGates: [[criticality: 'FAILURE', metric: 'LINE', threshold: config.context.lineCoverageThreshold]])
            if (steps.currentBuild.result == 'FAILURE') {
                steps.error "UT coverage failure!"
            }
        }
    }

    @Override
    def codeAnalysis() {
        if (!config.context.codeAnalysisEnabled) {
            return
        }

        steps.container('builder') {
            switch (config.java.buildTool) {
                case "maven":
                    String updateDependenciesArgs = ""
                    if (config.java.forceUpdateDependencies) {
                        updateDependenciesArgs = "-U"
                    }

                    steps.withSonarQubeEnv(config.context.sonarqubeServerName) {
                        steps.sh """
                            export MAVEN_OPTS="-Xmx2048m -XX:+HeapDumpOnOutOfMemoryError -Dfile.encoding=UTF-8"
                            cd "${config.srcRootPath}"
                            sh ./mvnw compile org.sonarsource.scanner.maven:sonar-maven-plugin:3.10.0.2594:sonar \
                                -s ./default-maven-settings.xml \
                                "-Dmaven.repo.local=\${MAVEN_USER_HOME}/repository" \
                                ${updateDependenciesArgs} \
                                -Dfile.encoding=UTF-8 \
                                -pl ${config.java.moduleName} -am
                        """
                    }
                    break
                case "gradle":
                    Utils.addGradlePlugin(steps, 'org.sonarqube', "4.4.1.3373",
                            "${config.srcRootPath}/${config.java.moduleName}")

                    String updateDependenciesArgs = ""
                    if (config.java.forceUpdateDependencies) {
                        updateDependenciesArgs = "--refresh-dependencies"
                    }

                    steps.withSonarQubeEnv(config.context.sonarqubeServerName) {
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

            if (config.context.qualityGateEnabled) {
                steps.timeout(time: config.context.sonarqubeTimeoutMinutes, unit: 'MINUTES') {
                    def qg = steps.waitForQualityGate()
                    if (qg.status != 'OK') {
                        steps.error "Quality gate failure: ${qg.status}"
                    }
                }
            }
        }
    }
}
