package com.ido.pipeline.base

import jenkins.model.*
import org.jenkinsci.plugins.workflow.steps.FlowInterruptedException
import java.util.TimeZone.*
import java.util.Date.*
import hudson.scm.*
import hudson.model.*
import com.ido.pipeline.Utils

/**
 * @author xinnj
 */
abstract class BuildPipeline extends BasePipeline {
    BuildPipeline(steps) {
        super(steps)
    }

    def customStages() {
        steps.stage('Prepare') {
            steps.echo "########## Stage: Prepare ##########"
            this.prepare()
        }

        steps.stage('SCM') {
            steps.echo "########## Stage: SCM ##########"
            this.scm()
        }

        if (steps.fileExists("${steps.WORKSPACE}/${config.srcRootPath}/${config.customerBuildScript.afterScm}")) {
            steps.stage('afterScm') {
                steps.echo "########## Stage: After Scm ##########"
                this.afterScm()
            }
        }

        steps.stage('Versioning') {
            steps.echo "########## Stage: Versioning ##########"
            this.versioning()
        }

        if (steps.fileExists("${steps.WORKSPACE}/${config.srcRootPath}/${config.customerBuildScript.afterVersioning}")) {
            steps.stage('afterVersioning') {
                steps.echo "########## Stage: After Versioning ##########"
                this.afterVersioning()
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

        if (config.parallelBuildArchive) {
            steps.parallel 'Build': {
                steps.stage('Build') {
                    steps.echo "########## Stage: Build ##########"
                    if (!this.customerBuild()) {
                        this.build()
                    }
                }

                if (steps.fileExists("${steps.WORKSPACE}/${config.srcRootPath}/${config.customerBuildScript.afterBuild}")) {
                    steps.stage('afterBuild') {
                        steps.echo "########## Stage: After Build ##########"
                        this.afterBuild()
                    }
                }
            }, 'Archive': {
                steps.stage('Archive') {
                    steps.echo "########## Stage: Archive ##########"
                    this.archive()
                }

                if (steps.fileExists("${steps.WORKSPACE}/${config.srcRootPath}/${config.customerBuildScript.afterArchive}")) {
                    steps.stage('afterArchive') {
                        steps.echo "########## Stage: After Archive ##########"
                        this.afterArchive()
                    }
                }
            }, failFast: true
        } else {
            steps.stage('Build') {
                steps.echo "########## Stage: Build ##########"
                if (!this.customerBuild()) {
                    this.build()
                }
            }

            if (steps.fileExists("${steps.WORKSPACE}/${config.srcRootPath}/${config.customerBuildScript.afterBuild}")) {
                steps.stage('afterBuild') {
                    steps.echo "########## Stage: After Build ##########"
                    this.afterBuild()
                }
            }

            steps.stage('Archive') {
                steps.echo "########## Stage: Archive ##########"
                this.archive()
            }

            if (steps.fileExists("${steps.WORKSPACE}/${config.srcRootPath}/${config.customerBuildScript.afterArchive}")) {
                steps.stage('afterArchive') {
                    steps.echo "########## Stage: After Archive ##########"
                    this.afterArchive()
                }
            }
        }

        if (config.jobsInvoked.size() != 0) {
            steps.echo "########## Stage: Invoke ##########"
            this.invoke(null)
        }
    }

    def prepare() {
        if (!config.productName) {
            steps.error "productName is empty!"
        }

        super.prepare()
    }

    def abstract ut()

    def abstract codeAnalysis()

    def beforeBuild() {
        steps.container('builder') {
            steps.withEnv(["CI_PRODUCTNAME=$config.productName",
                           "CI_VERSION=$config.version",
                           "CI_BRANCH=" + Utils.getBranchName(steps)]) {
                runCustomerBuildScript(config.customerBuildScript.beforeBuild)
            }
        }
    }

    def abstract build()

    Boolean customerBuild() {
        Boolean runCustomer = false
        steps.container('builder') {
            if (steps.fileExists("${steps.WORKSPACE}/${config.srcRootPath}/${config.customerBuildScript.build}")) {
                steps.echo "Execute customer build script: ${config.customerBuildScript.build}"
                steps.withEnv(["CI_PRODUCTNAME=$config.productName",
                               "CI_VERSION=$config.version",
                               "CI_BRANCH=" + Utils.getBranchName(steps)]) {
                    runCustomerBuildScript(config.customerBuildScript.build)
                }
                runCustomer = true
            }
        }
        return runCustomer
    }

    def afterBuild() {
        steps.container('builder') {
            steps.withEnv(["CI_PRODUCTNAME=$config.productName",
                           "CI_VERSION=$config.version",
                           "CI_BRANCH=" + Utils.getBranchName(steps)]) {
                runCustomerBuildScript(config.customerBuildScript.afterBuild)
            }
        }
    }

    def abstract archive()

    def afterArchive() {
        steps.container('builder') {
            steps.withEnv(["CI_PRODUCTNAME=$config.productName",
                           "CI_VERSION=$config.version",
                           "CI_BRANCH=" + Utils.getBranchName(steps)]) {
                runCustomerBuildScript(config.customerBuildScript.afterArchive)
            }
        }
    }
}

