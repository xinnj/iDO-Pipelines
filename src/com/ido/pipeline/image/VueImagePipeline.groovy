package com.ido.pipeline.image

import com.ido.pipeline.archiver.ImageArchiver
import com.ido.pipeline.languageBase.NpmPipeline

/**
 * @author xinnj
 */
class VueImagePipeline extends NpmPipeline {
    ImageArchiver imageArchiver

    VueImagePipeline(Object steps) {
        super(steps)
    }

    @Override
    def prepare() {
        super.prepare()

        imageArchiver = new ImageArchiver(steps, config)

        if (!config.vue.baseImage) {
            steps.error "vue.baseImage is empty!"
        }

        config.put("context", config.vue)
        config.parallelBuildArchive = true
        config.put("utTool", "vitest")
    }

    @Override
    def build() {
        steps.container('builder') {
            steps.sh """${config.debugSh}
                cd "${config.srcRootPath}"
        
                npm install
                npm run build
            """
        }

        if (!config.vue.nginxConfigFile) {
            String nginxConf = steps.libraryResource(resource: 'builder/default-nginx-config', encoding: 'UTF-8')
            steps.writeFile(file: "${config.srcRootPath}/nginx.conf", text: nginxConf, encoding: 'UTF-8')
            config.vue.nginxConfigFile = "nginx.conf"
        }

        if (!config.dockerFile) {
            String defaultDockerfile = steps.libraryResource(resource: 'builder/default-vue-dockerfile', encoding: 'UTF-8')
            defaultDockerfile = defaultDockerfile
                    .replaceAll('<baseImage>', config.vue.baseImage as String)
                    .replaceAll('<nginxConfigFile>', config.vue.nginxConfigFile as String)
            config.put("defaultDockerfile", defaultDockerfile)
        }

        imageArchiver.buildImage()
    }

    @Override
    def archive() {
        imageArchiver.buildHelm()
    }
}
