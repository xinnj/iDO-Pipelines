package com.ido.pipeline.base

import jenkins.model.*
import org.jenkinsci.plugins.workflow.steps.FlowInterruptedException
import java.util.TimeZone.*
import java.util.Date.*
import hudson.scm.*
import hudson.model.*
import com.ido.pipeline.sdk.SdkDownloader

/**
 * @author xinnj
 */
abstract class BuildPipeline extends BasePipeline {
    BuildPipeline(steps) {
        super(steps)
    }

    @Override
    def customStages() {
        steps.stage('Prepare') {
            steps.echo "\033[32m########## Stage: Prepare ##########\033[0m"
            this.prepare()
        }

        steps.stage('SCM') {
            steps.echo "\033[32m########## Stage: SCM ##########\033[0m"
            this.scm()
        }

        if (checkCustomerBuildScript(config.customerBuildScript.afterScm)) {
            steps.stage('afterScm') {
                steps.echo "\033[32m########## Stage: After Scm ##########\033[0m"
                this.afterScm()
            }
        }

        steps.stage('Versioning') {
            steps.echo "\033[32m########## Stage: Versioning ##########\033[0m"
            this.versioning()
        }

        if (checkCustomerBuildScript(config.customerBuildScript.afterVersioning)) {
            steps.stage('afterVersioning') {
                steps.echo "\033[32m########## Stage: After Versioning ##########\033[0m"
                this.afterVersioning()
            }
        }

        if (config.parallelUtAnalysis) {
            steps.parallel 'UT': {
                steps.stage('UT') {
                    steps.echo "\033[32m########## Stage: UT ##########\033[0m"
                    this.ut()
                }
            }, 'Code Analysis': {
                steps.stage('Code Analysis') {
                    steps.echo "\033[32m########## Stage: Code Analysis ##########\033[0m"
                    this.codeAnalysis()
                }
            }, failFast: true
        } else {
            steps.stage('UT') {
                steps.echo "\033[32m########## Stage: UT ##########\033[0m"
                this.ut()
            }

            steps.stage('Code Analysis') {
                steps.echo "\033[32m########## Stage: Code Analysis ##########\033[0m"
                this.codeAnalysis()
            }
        }

        if (checkCustomerBuildScript(config.customerBuildScript.beforeBuild)) {
            steps.stage('beforeBuild') {
                steps.echo "\033[32m########## Stage: Before Build ##########\033[0m"
                this.beforeBuild()
            }
        }

        if (config.parallelBuildArchive) {
            steps.parallel 'Build': {
                steps.stage('Build') {
                    steps.echo "\033[32m########## Stage: Build ##########\033[0m"
                    if (!this.customerBuild()) {
                        this.build()
                    }
                }

                if (checkCustomerBuildScript(config.customerBuildScript.afterBuild)) {
                    steps.stage('afterBuild') {
                        steps.echo "\033[32m########## Stage: After Build ##########\033[0m"
                        this.afterBuild()
                    }
                }
            }, 'Archive': {
                steps.stage('Archive') {
                    steps.echo "\033[32m########## Stage: Archive ##########\033[0m"
                    this.archive()
                }

                if (checkCustomerBuildScript(config.customerBuildScript.afterArchive)) {
                    steps.stage('afterArchive') {
                        steps.echo "\033[32m########## Stage: After Archive ##########\033[0m"
                        this.afterArchive()
                    }
                }
            }, failFast: true
        } else {
            steps.stage('Build') {
                steps.echo "\033[32m########## Stage: Build ##########\033[0m"
                if (!this.customerBuild()) {
                    this.build()
                }
            }

            if (checkCustomerBuildScript(config.customerBuildScript.afterBuild)) {
                steps.stage('afterBuild') {
                    steps.echo "\033[32m########## Stage: After Build ##########\033[0m"
                    this.afterBuild()
                }
            }

            steps.stage('Archive') {
                steps.echo "\033[32m########## Stage: Archive ##########\033[0m"
                this.archive()
            }

            if (checkCustomerBuildScript(config.customerBuildScript.afterArchive)) {
                steps.stage('afterArchive') {
                    steps.echo "\033[32m########## Stage: After Archive ##########\033[0m"
                    this.afterArchive()
                }
            }
        }

        if (config.jobsInvoked.size() != 0) {
            steps.echo "\033[32m########## Stage: Invoke ##########\033[0m"
            this.invoke(null)
        }
    }

    @Override
    def prepare() {
        if (!config.productName) {
            steps.error "productName is empty!"
        }

        super.prepare()
    }

    @Override
    def versioning() {
        super.versioning()

        SdkDownloader sdkDownloader = new SdkDownloader(steps, config)
        if (sdkDownloader.updateSdkInfo()) {
            steps.currentBuild.displayName = "SDK Info Updated"
            // if sdk info is updated, finish this build as a new build is triggered
            fastStop = 'SUCCESS'
            steps.error "Fast stop this build with success."
        }

        sdkDownloader.downloadSdk()
    }

    def abstract ut()

    def abstract codeAnalysis()

    def beforeBuild() {
        runCustomerBuildScript(config.customerBuildScript.beforeBuild)
    }

    def abstract build()

    Boolean customerBuild() {
        Boolean runCustomer = false
        if (checkCustomerBuildScript(config.customerBuildScript.build)) {
            steps.echo "Execute customer build script: ${config.customerBuildScript.build}"
            runCustomerBuildScript(config.customerBuildScript.build)
            runCustomer = true
        }
        return runCustomer
    }

    def afterBuild() {
        runCustomerBuildScript(config.customerBuildScript.afterBuild)
    }

    def abstract archive()

    def afterArchive() {
        runCustomerBuildScript(config.customerBuildScript.afterArchive)
    }
}

