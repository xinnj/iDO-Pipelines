package com.ido.pipeline.languageBase

/**
 * @author xinnj
 */
abstract class AndroidPipeline extends JdkPipeline{
    AndroidPipeline(Object steps) {
        super(steps)
        this.useK8sAgent = true
        this.nodeName = "android"
    }

    @Override
    Map runPipeline(Map config) {
        if (config.useK8sAgent == null || config.useK8sAgent) {
            String builder = steps.libraryResource(resource: 'pod-template/android-builder.yaml', encoding: 'UTF-8')
            builder = builder.replaceAll('<builderImage>', config.java.builderImage)
                    .replaceAll('<androidCmdLineToolsUrl>', config._system.android.cmdLineToolsUrl)
            config.podTemplate = builder
        }

        return super.runPipeline(config)
    }

    @Override
    def prepare() {
        super.prepare()

        config.java.buildTool = "gradle"
        config.put("context", config.android)
        config.parallelBuildArchive = false

        String sdkPackages = ""

        String proxy = ""
        if (config._system.android.proxy.enable) {
            proxy = "--no_https --proxy=http --proxy_host=${config._system.android.proxy.host} --proxy_port=${config._system.android.proxy.port}"
        }

        if (config.android.sdkPackagesRequired) {
            List<String> sdkPackagesInstalledOutput = execOnAgent('builder', {
                steps.sh(returnStdout: true, encoding: "UTF-8", script: """${config.debugSh}
                    \${ANDROID_HOME}/cmdline-tools/latest/bin/sdkmanager --list_installed | tail -n +4 | awk '{print \$1}'
                """)
                        .trim()
                        .split(System.lineSeparator()) as List<String>
            })

            Boolean start = false
            def sdkPackagesInstalled = []
            for (int i = 0; i < sdkPackagesInstalledOutput.size(); i++) {
                if (start) {
                    sdkPackagesInstalled.add(sdkPackagesInstalledOutput[i])
                }
                if (sdkPackagesInstalledOutput[i].matches("^\\s*----.*")) {
                    start = true
                }
            }

            steps.echo "sdkPackagesInstalled: ${sdkPackagesInstalled}"

            List<String> sdkPackagesToBeInstalled = (config.android.sdkPackagesRequired as List<String>)
                    .findAll { !sdkPackagesInstalled.contains(it) }


            sdkPackagesToBeInstalled.each {
                sdkPackages = sdkPackages + "'$it' "
            }
            steps.echo "sdkPackages: ${sdkPackages}"
        }

        execOnAgent('builder', {
            steps.sh """${config.debugSh}
                if [ ! -e \${ANDROID_HOME}/licenses-agreed ]; then
                    yes | \${ANDROID_HOME}/cmdline-tools/latest/bin/sdkmanager ${proxy} --licenses
                    touch \${ANDROID_HOME}/licenses-agreed
                fi
                if [ -n "${sdkPackages}" ]; then
                    yes | \${ANDROID_HOME}/cmdline-tools/latest/bin/sdkmanager ${proxy} --install $sdkPackages
                fi
            """
        })
    }


    def abstract build()

    def abstract archive()
}
