package com.ido.pipeline.image
/**
 * @author xinnj
 */
class NodejsImagePipeline extends ImagePipeline {
    NodejsImagePipeline(Object steps) {
        super(steps)
    }

    @Override
    Map runPipeline(Map config) {
        config.nodeType = "k8s"
        String nodejsBuilder = steps.libraryResource(resource: 'pod-template/npm-builder.yaml', encoding: 'UTF-8')
        nodejsBuilder = nodejsBuilder.replaceAll('<builderImage>', config.nodejs.baseImage)
        config.podTemplate = nodejsBuilder

        return super.runPipeline(config)
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

        if (config.nodejs.useDefaultNpmrc) {
            String npmrc = steps.libraryResource(resource: 'builder/default-npmrc', encoding: 'UTF-8')
            steps.writeFile(file: "${config.srcRootPath}/.npmrc", text: settings, encoding: "UTF-8")
        }

        steps.container('builder') {
            steps.sh """#!/bin/sh
                cd "${config.srcRootPath}"
                npm -v
            """
        }
    }

    @Override
    def ut() {
    }

    @Override
    def codeAnalysis() {
    }

    @Override
    def build() {

    }
}
