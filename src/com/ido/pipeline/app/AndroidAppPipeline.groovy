package com.ido.pipeline.app

import com.ido.pipeline.Utils
import com.ido.pipeline.base.Artifact

/**
 * @author xinnj
 */
class AndroidAppPipeline extends AppPipeline {
    AndroidAppPipeline(Object steps) {
        super(steps)
    }

    @Override
    Map runPipeline(Map config) {
        config.nodeType = "k8s"

        String builder = steps.libraryResource(resource: 'pod-template/android-builder.yaml', encoding: 'UTF-8')
        builder = builder.replaceAll('<builderImage>', config.java.builderImage)
                .replaceAll('<androidCmdLineToolsUrl>', config._system.androidCmdLineToolsUrl)
        config.podTemplate = builder

        return super.runPipeline(config)
    }

    @Override
    def prepare() {
        super.prepare()

        if (!config.android.sdkPackagesRequired) {
            steps.error "sdkPackagesRequired is empty!"
        }

        String sdkPackages = ""
        (config.android.sdkPackagesRequired as List).each {
            sdkPackages = sdkPackages + "\"$it\" "
        }

        steps.container('builder') {
            steps.sh """
                yes | \${ANDROID_HOME}/cmdline-tools/latest/bin/sdkmanager --licenses
                yes | \${ANDROID_HOME}/cmdline-tools/latest/bin/sdkmanager --install $sdkPackages
            """
        }
    }

    @Override
    def scm() {
        super.scm()

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
    }

    @Override
    def ut() {
        if (!config.android.utEnabled) {
            return
        }

        steps.container('builder') {
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

            steps.jacoco(changeBuildStatus: true, minimumLineCoverage: "${config.android.lineCoverageThreshold}",
                    maximumLineCoverage: "${config.android.lineCoverageThreshold}")
            if (steps.currentBuild.result == 'FAILURE') {
                steps.error "UT coverage failure!"
            }
        }
    }

    @Override
    def codeAnalysis() {
        if (!config.android.codeAnalysisEnabled) {
            return
        }
        steps.container('builder') {
            Utils.addGradlePlugin(steps, 'org.sonarqube', "4.0.0.2929", "${config.srcRootPath}/${config.java.moduleName}")

            String updateDependenciesArgs = ""
            if (config.java.forceUpdateDependencies) {
                updateDependenciesArgs = "--refresh-dependencies"
            }

            steps.withSonarQubeEnv(config.android.sonarqubeServerName) {
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

            if (config.android.qualityGateEnabled) {
                steps.timeout(time: config.android.sonarqubeTimeoutMinutes, unit: 'MINUTES') {
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
        String newFileName = this.getFileName()
        steps.container('builder') {
            String updateDependenciesArgs = ""
            if (config.java.forceUpdateDependencies) {
                updateDependenciesArgs = "--refresh-dependencies"
            }

            steps.withEnv(["CI_PRODUCTNAME=$config.productName",
                           "CI_VERSION=$config.version",
                           "CI_BRANCH=" + Utils.getBranchName(steps)]) {
                steps.sh """
                    cd "${config.srcRootPath}"
                    mkdir -p outputs/files
                    rm -f outputs/files/*

                    if [ -s "./${config.buildScript}" ]; then
                        sh "./${config.buildScript}"
                    else
                        if [ "$config.android.buildDebug" == "true" ]; then
                            sh ./gradlew assembleDebug \
                                --no-daemon \
                                -x test \
                                ${updateDependenciesArgs} \
                                -I "${steps.env.WORKSPACE}/${config.srcRootPath}/default-gradle-init.gradle" \
                                -Dfile.encoding=UTF-8 \
                                "-Dorg.gradle.jvmargs=-Xmx2048m -XX:+HeapDumpOnOutOfMemoryError -Dfile.encoding=UTF-8" \
                                -p ${config.java.moduleName}
                            numFound=\$(find ./ -type f -name "*-debug*.apk" -not -path "./outputs/*" -print | wc -l)
                            if [ \$numFound -lt 1 ]; then
                                echo "Can't find debug version apk file!"
                                exit 1
                            fi
                            if [ \$numFound -gt 1 ]; then
                                echo "Find multiple debug version apk files!"
                                exit 1
                            fi
                            find ./ -type f -name "*-debug*.apk" -not -path "./outputs/*" \
                                -exec mv "{}" "outputs/files/${newFileName}-debug.apk" \\;
                        fi
                        if [ "$config.android.buildRelease" == "true" ]; then
                            sh ./gradlew assembleRelease \
                                --no-daemon \
                                -x test \
                                ${updateDependenciesArgs} \
                                -I "${steps.env.WORKSPACE}/${config.srcRootPath}/default-gradle-init.gradle" \
                                -Dfile.encoding=UTF-8 \
                                "-Dorg.gradle.jvmargs=-Xmx2048m -XX:+HeapDumpOnOutOfMemoryError -Dfile.encoding=UTF-8" \
                                -p ${config.java.moduleName}
                            numFound=\$(find ./ -type f -name "*-release*.apk" -not -path "./outputs/*" -print | wc -l)
                            if [ \$numFound -lt 1 ]; then
                                echo "Can't find release version apk file!"
                                exit 1
                            fi
                            if [ \$numFound -gt 1 ]; then
                                echo "Find multiple release version apk files!"
                                exit 1
                            fi
                            find ./ -type f -name "*-release*.apk" -not -path "./outputs/*" \
                                -exec mv "{}" "outputs/files/${newFileName}-release.apk" \\;
                        fi
                    fi
                """
            }
        }
    }

    @Override
    def archive() {
        String newFileName = this.getFileName()
        steps.container('uploader') {
            String uploadUrl = "${config.fileServer.uploadUrl}${config.fileServer.uploadRootPath}${config.productName}/" +
                    Utils.getBranchName(steps) + "/android"
            String debugDownloadUrl = "${config.fileServer.downloadUrl}${config.fileServer.uploadRootPath}/${config.productName}/" +
                    Utils.getBranchName(steps) + "/android/files/${newFileName}-debug.apk"
            String releaseDownloadUrl = "${config.fileServer.downloadUrl}${config.fileServer.uploadRootPath}/${config.productName}/" +
                    Utils.getBranchName(steps) + "/android/files/${newFileName}-release.apk"


            steps.sh """
                cd "${config.srcRootPath}/outputs"
                touch ${newFileName}.html
                echo "<html>" >> ${newFileName}.html
                echo "<meta http-equiv='Content-Type' content='text/html; charset=utf-8' />" >> ${newFileName}.html
                
                if [ "${config.android.buildDebug}" == "true" ]; then
                    qrencode --output qrcode.png "${debugDownloadUrl}"
                    if [ ! -f qrcode.png ]; then
                        echo QR code is not generated!
                        exit 1
                    fi
                    debugQrcode="\$(cat qrcode.png | base64 )"
                    rm -f qrcode.png
                    
                    echo "<hr><p><a href='files/${newFileName}-debug.apk'>${newFileName}-debug.apk</a>" >> ${newFileName}.html
                    echo "<p><img src='data:image/png;base64,\${debugQrcode}'/>" >> ${newFileName}.html
                fi
                
                if [ "${config.android.buildRelease}" == "true" ]; then
                    qrencode --output qrcode.png "${releaseDownloadUrl}"
                    if [ ! -f qrcode.png ]; then
                        echo QR code is not generated!
                        exit 1
                    fi
                    releaseQrcode="\$(cat qrcode.png | base64 )"
                    rm -f qrcode.png
                    
                    echo "<hr><p><a href='files/${newFileName}-release.apk'>${newFileName}-release.apk</a>" >> ${newFileName}.html
                    echo "<p><img src='data:image/png;base64,\${releaseQrcode}'/>" >> ${newFileName}.html
                fi
            """

            Artifact artifact = new Artifact()
            artifact.uploadToFileServer(steps, uploadUrl, "${config.srcRootPath}/outputs")
        }
    }

    String getFileName() {
        String branch = Utils.getBranchName(steps)
        return "${config.productName}-${branch}-${config.version}"
    }
}
