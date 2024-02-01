package com.ido.pipeline.image

import com.ido.pipeline.Utils
import com.ido.pipeline.languageBase.LanguageNodejs

/**
 * @author xinnj
 */
class NodejsImagePipeline extends ImagePipeline {
    NodejsImagePipeline(Object steps) {
        super(steps)
    }

    @Override
    Map runPipeline(Map config) {
        LanguageNodejs.runPipeline(config, steps)
        return super.runBasePipeline(config)
    }

    @Override
    def prepare() {
        super.prepare()

        if (!config.nodejs.baseImage) {
            steps.error "nodejs.baseImage is empty!"
        }
    }

    @Override
    def scm() {
        super.scm()
        LanguageNodejs.scm(config, steps)
    }

    @Override
    def ut() {
        if (!config.nodejs.utEnabled) {
            return
        }

        LanguageNodejs.ut(config, steps)
    }

    @Override
    def codeAnalysis() {
        if (!config.nodejs.codeAnalysisEnabled) {
            return
        }

        LanguageNodejs.codeAnalysis(config, steps)
    }

    @Override
    def build() {
        if (this.customerBuild()) {
            return
        }

        LanguageNodejs.build(config, steps)

        if (!steps.fileExists("${config.srcRootPath}/.dockerignore")) {
            String dockerignore = steps.libraryResource(resource: 'builder/default-dockerignore', encoding: 'UTF-8')
            steps.writeFile(file: "${config.srcRootPath}/.dockerignore", text: dockerignore, encoding: 'UTF-8')
        }
        if (!config.dockerFile) {
            String dockerfile = steps.libraryResource(resource: 'builder/default-nodejs-dockerfile', encoding: 'UTF-8')
            dockerfile = dockerfile
                    .replaceAll('<baseImage>', config.nodejs.baseImage as String)
                    .replaceAll('<startCmd>', config.nodejs.StartCmd as String)

            if (config._system.imagePullMirror) {
                dockerfile = Utils.replaceImageMirror(config._system.imageMirrors, dockerfile)
            }

            steps.writeFile(file: "${config.srcRootPath}/Dockerfile", text: dockerfile, encoding: 'UTF-8')
            config.dockerFile = "Dockerfile"
        }

        this.buildImage()
    }
}
