package com.ido.pipeline.image

import com.ido.pipeline.archiver.ImageArchiver
import com.ido.pipeline.languageBase.NpmPipeline

/**
 * @author xinnj
 */
class NodejsImagePipeline extends NpmPipeline {
    ImageArchiver imageArchiver

    NodejsImagePipeline(Object steps) {
        super(steps)
    }

    @Override
    def prepare() {
        super.prepare()

        imageArchiver = new ImageArchiver(steps, config)

        if (!config.npm.builderImage) {
            steps.error "npm.builderImage is empty!"
        }
        if (!config.nodejs.baseImage) {
            config.nodejs.baseImage = config.npm.builderImage
        }

        config.put("context", config.nodejs)
        config.parallelBuildArchive = false
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
    }

    @Override
    def archive() {
        if (!config.dockerFile) {
            String defaultDockerfile = steps.libraryResource(resource: 'builder/default-nodejs-dockerfile', encoding: 'UTF-8')
            defaultDockerfile = defaultDockerfile
                    .replaceAll('<baseImage>', config.nodejs.baseImage as String)
                    .replaceAll('<startCmd>', config.nodejs.StartCmd as String)
            config.put("defaultDockerfile", defaultDockerfile)
        }

        imageArchiver.buildImage()

        imageArchiver.buildHelm()
    }
}
