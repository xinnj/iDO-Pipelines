package com.ido.pipeline.image

import com.ido.pipeline.Utils
import com.ido.pipeline.languageBase.JdkPipeline

/**
 * @author xinnj
 */
class SpringBootImagePipeline extends JdkPipeline {
    SpringBootImagePipeline(Object steps) {
        super(steps)
    }

    @Override
    def prepare() {
        super.prepare()

        if (!config.springBoot.baseImage) {
            steps.error "springBoot.baseImage is empty!"
        }

        config.put("context", config.springBoot)
        config.parallelBuildArchive = true
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
            config.springBoot.baseImage = Utils.replaceImageMirror(config._system.imageMirrors, config.springBoot.baseImage)
        }
        config.springBoot.baseImage = (config.springBoot.baseImage as String).replaceAll("^docker.io/", "")

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
                        sh ./mvnw compile com.google.cloud.tools:jib-maven-plugin:3.4.0:build \
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
                Utils.addGradlePlugin(steps, 'com.google.cloud.tools.jib', '3.4.0', "${config.srcRootPath}/${config.java.moduleName}")

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

    @Override
    def archive() {
        new ImageHelper(steps, config).buildHelm()
    }
}
