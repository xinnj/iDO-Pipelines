package com.ido.pipeline.image

import com.ido.pipeline.languageBase.NpmPipeline

/**
 * @author xinnj
 */
class NodejsImagePipeline extends NpmPipeline {
    ImageHelper imageHelper

    NodejsImagePipeline(Object steps) {
        super(steps)
    }

    @Override
    def prepare() {
        super.prepare()

        imageHelper = new ImageHelper(steps, config)

        if (!config.npm.builderImage) {
            steps.error "npm.builderImage is empty!"
        }
        if (!config.nodejs.baseImage) {
            config.nodejs.baseImage = config.npm.builderImage
        }

        config.put("context", config.nodejs)
        config.parallelBuildArchive = true
        config.put("utTool", "jest")
    }

    @Override
    def build() {
        steps.container('builder') {
            steps.sh """${config.debugSh}
                export NODE_ENV=production
                cd "${config.srcRootPath}"

                npm ci --omit=dev
            """
        }

        if (!config.dockerFile) {
            String defaultDockerfile = steps.libraryResource(resource: 'builder/default-nodejs-dockerfile', encoding: 'UTF-8')
            defaultDockerfile = defaultDockerfile
                    .replaceAll('<baseImage>', config.nodejs.baseImage as String)
                    .replaceAll('<startCmd>', config.nodejs.StartCmd as String)
            config.put("defaultDockerfile", defaultDockerfile)
        }

        imageHelper.buildImage()
    }

    @Override
    def archive() {
        imageHelper.buildHelm()
    }
}
