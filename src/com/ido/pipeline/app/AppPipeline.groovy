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
        def result = super.runPipeline(config)
        return result
    }

    @Override
    def customStages() {
        super.customStages()

        if (steps.fileExists("${steps.WORKSPACE}/${config.srcRootPath}/${config.customerBuildScript.afterScm}")) {
            steps.stage('afterScm') {
                steps.echo "########## Stage: After Scm ##########"
                this.afterScm()
            }
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

        if (steps.fileExists("${steps.WORKSPACE}/${config.srcRootPath}/${config.customerBuildScript.beforeBuild}")) {
            steps.stage('beforeBuild') {
                steps.echo "########## Stage: Before Build ##########"
                this.beforeBuild()
            }
        }

        steps.stage('Build App') {
            steps.echo "########## Stage: Build App ##########"
            this.build()
        }

        if (steps.fileExists("${steps.WORKSPACE}/${config.srcRootPath}/${config.customerBuildScript.afterBuild}")) {
            steps.stage('afterBuild') {
                steps.echo "########## Stage: After Build ##########"
                this.afterBuild()
            }
        }

        steps.stage('Archive App') {
            steps.echo "########## Stage: Archive App ##########"
            this.archive()
        }

        if (config.jobsInvoked.size() != 0) {
            steps.echo "########## Stage: Invoke ##########"
            this.invoke(null)
        }
    }

    @Override
    def prepare() {
        super.prepare()
    }

    def afterScm() {
        steps.container('builder') {
            if (steps.isUnix()) {
                steps.sh """
                    cd "${config.srcRootPath}"
                    sh "${config.customerBuildScript.afterScm}"
                """
            } else {
                steps.powershell """
                    cd "${config.srcRootPath}"
                    "${config.customerBuildScript.afterScm}"
                """
            }
        }
    }

    def abstract ut()

    def abstract codeAnalysis()

    def beforeBuild() {
        steps.container('builder') {
            if (steps.isUnix()) {
                steps.sh """
                    cd "${config.srcRootPath}"
                    sh "${config.customerBuildScript.beforeBuild}"
                """
            } else {
                steps.powershell """
                    cd "${config.srcRootPath}"
                    "${config.customerBuildScript.beforeBuild}"
                """
            }
        }
    }

    def abstract build()

    def afterBuild() {
        steps.container('builder') {
            if (steps.isUnix()) {
                steps.sh """
                    cd "${config.srcRootPath}"
                    sh "${config.customerBuildScript.afterBuild}"
                """
            } else {
                steps.powershell """
                    cd "${config.srcRootPath}"
                    "${config.customerBuildScript.afterBuild}"
                """
            }
        }
    }

    def abstract archive()
}