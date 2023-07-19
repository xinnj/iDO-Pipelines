package com.ido.pipeline.image

import com.ido.pipeline.Utils

/**
 * @author xinnj
 */
class VueImagePipeline extends ImagePipeline {
    VueImagePipeline(Object steps) {
        super(steps)
    }

    @Override
    Map runPipeline(Map config) {
        config.nodeType = "k8s"
        config.parallelUtAnalysis = true

        String vueBuilder = steps.libraryResource(resource: 'pod-template/npm-builder.yaml', encoding: 'UTF-8')
        vueBuilder = vueBuilder.replaceAll('<builderImage>', config.vue.builderBaseImage)
        config.podTemplate = vueBuilder

        return super.runBasePipeline(config)
    }

    @Override
    def prepare() {
        super.prepare()

        if (!config.vue.runtimeBaseImage) {
            steps.error "vue.runtimeBaseImage is empty!"
        }
    }

    @Override
    def scm() {
        super.scm()

        if (config.vue.useDefaultNpmrc) {
            String npmrc = steps.libraryResource(resource: 'builder/default-npmrc', encoding: 'UTF-8')
            steps.writeFile(file: "${config.srcRootPath}/.npmrc", text: npmrc, encoding: "UTF-8")
        }

        steps.container('builder') {
            steps.sh """
                cd "${config.srcRootPath}"
                echo "npm version:"
                npm -v
                echo "node version:"
                node -v
            """
        }
    }

    @Override
    def ut() {
        if (!config.vue.utEnabled) {
            return
        }

        steps.container('builder') {
            /* Following config is needed in vite.config.js

            test: {
                coverage: {
                    provider: 'istanbul',
                    reporter: ['text', 'cobertura']
                },
            }

            */
            steps.sh """
                cd "${config.srcRootPath}"
                npm i -D @vitest/coverage-istanbul
                npm install-test
            """

            def files = steps.findFiles(glob: "${config.srcRootPath}/**/cobertura-coverage.xml")
            if (files) {
                steps.echo "Coverage report file: ${files[0].path}"
                steps.cobertura(coberturaReportFile: files[0].path, enableNewApi: true,
                        lineCoverageTargets: "${config.vue.lineCoverageThreshold}, ${config.vue.lineCoverageThreshold}, ${config.vue.lineCoverageThreshold}")
            } else {
                steps.error "Can't find coverage report file!"
            }
        }
    }

    @Override
    def codeAnalysis() {
        if (!config.vue.codeAnalysisEnabled) {
            return
        }

        steps.container('sonar-scanner') {
            steps.withSonarQubeEnv(config.vue.sonarqubeServerName) {
                steps.sh """
                    cd "${config.srcRootPath}"
                    sonar-scanner -Dsonar.projectKey=${config.productName} -Dsonar.sourceEncoding=UTF-8
                """
            }
        }

        if (config.vue.qualityGateEnabled) {
            steps.timeout(time: config.vue.sonarqubeTimeoutMinutes, unit: 'MINUTES') {
                def qg = steps.waitForQualityGate()
                if (qg.status != 'OK') {
                    steps.error "Quality gate failure: ${qg.status}"
                }
            }
        }
    }

    @Override
    def build() {
        if (this.customerBuild()) {
            return
        }

        steps.container('builder') {
            steps.sh """
                cd "${config.srcRootPath}"
        
                npm install
                npm run build
            """
        }

        if (!steps.fileExists("${config.srcRootPath}/.dockerignore")) {
            String dockerignore = steps.libraryResource(resource: 'builder/default-dockerignore', encoding: 'UTF-8')
            steps.writeFile(file: "${config.srcRootPath}/.dockerignore", text: dockerignore, encoding: 'UTF-8')
        }

        if (!config.vue.nginxConfigFile) {
            String nginxConf = steps.libraryResource(resource: 'builder/default-nginx-config', encoding: 'UTF-8')
            steps.writeFile(file: "${config.srcRootPath}/nginx.conf", text: nginxConf, encoding: 'UTF-8')
            config.vue.nginxConfigFile = "nginx.conf"
        }

        if (!config.dockerFile) {
            String dockerfile = steps.libraryResource(resource: 'builder/default-vue-dockerfile', encoding: 'UTF-8')
            dockerfile = dockerfile
                    .replaceAll('<baseImage>', config.vue.runtimeBaseImage as String)
                    .replaceAll('<nginxConfigFile>', config.vue.nginxConfigFile as String)

            if (config._system.imagePullMirror) {
                dockerfile = Utils.replaceImageMirror(config._system.imageMirrors, dockerfile)
            }

            steps.writeFile(file: "${config.srcRootPath}/Dockerfile", text: dockerfile, encoding: 'UTF-8')
            config.dockerFile = "Dockerfile"
        }

        this.buildImage()
    }


}
