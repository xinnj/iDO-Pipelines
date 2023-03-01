package com.ido.pipeline.app

import com.ido.pipeline.base.BasePipeline

/**
 * @author xinnj
 */
abstract class AppPipeline extends BasePipeline {
    AppPipeline(Object steps) {
        super(steps)
    }

    @Override
    Map runPipeline(Map config) {
        config.podTemplate = (config.podTemplate as String)
                .replaceAll('<keepBuilderPodMinutes>', (config.keepBuilderPodMinutes).toString())
                .replaceAll('<imagePullSecret>', config._system.imagePullSecret as String)

        def result = super.runPipeline(config)
        return result
    }

    @Override
    def customStages() {
        steps.stage('Prepare') {
            steps.echo "########## Stage: Prepare ##########"
            this.prepare()
        }

        steps.stage('SCM') {
            steps.echo "########## Stage: SCM ##########"
            this.scm()
        }

        steps.stage('Versioning') {
            steps.echo "########## Stage: Versioning ##########"
            this.versioning()
        }

        if (config.parallelUtAnalysis) {
            steps.parallel 'UT': {
                steps.stage('UT') {
                    steps.echo "########## Stage: UT ##########"
                    this.ut()
                }
            }, 'Code Analysis': {
                steps.stage('Code Analysis') {
                    steps.echo "########## Stage: Code Analysis ##########"
                    this.codeAnalysis()
                }
            }, failFast: true
        } else {
            steps.stage('UT') {
                steps.echo "########## Stage: UT ##########"
                this.ut()
            }

            steps.stage('Code Analysis') {
                steps.echo "########## Stage: Code Analysis ##########"
                this.codeAnalysis()
            }
        }

        steps.stage('Build App') {
            steps.echo "########## Stage: Build App ##########"
            this.build()
        }

        steps.stage('Archive App') {
            steps.echo "########## Stage: Archive App ##########"
            this.archive()
        }
    }

    @Override
    def prepare() {
        super.prepare()
    }

    def abstract ut()

    def abstract codeAnalysis()

    def abstract archive()
}