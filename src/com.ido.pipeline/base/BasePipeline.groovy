package com.ido.pipeline.base

import jenkins.model.*
import org.jenkinsci.plugins.workflow.steps.FlowInterruptedException
import java.util.TimeZone.*
import java.util.Date.*
import hudson.scm.*
import hudson.model.*

/**
 * @author xinnj
 */
abstract class BasePipeline implements Pipeline, Serializable {
    def steps
    String fastStop = ""
    String PATH_SEPARATOR

    BasePipeline(steps) {
        this.steps = steps
    }

    Map runPipeline(Map config) {
        def result = [:]

        if (config.stopAllRunningBuild) {
            // Stop all running build of the same job
            this.stopCurrentJob()
        }

        steps.node(config.nodeName) {
            PATH_SEPARATOR = Utils.getPathSeparator(steps)

            try {
                customStages(config)
                steps.currentBuild.result = 'SUCCESS'
            }
            catch (FlowInterruptedException interruptEx) {
                steps.echo 'Received FlowInterruptedException'
                steps.currentBuild.result = 'ABORTED'
            }
            catch (Exception e) {
                if (fastStop != "") {
                    steps.currentBuild.result = fastStop
                } else {
                    steps.echo 'Exception occurred: ' + e.toString()
                    steps.currentBuild.result = 'FAILURE'
                }
            }
            finally {
                steps.echo "########## Stage: Notify ##########"
                this.notify(config)
            }
        }

        return result
    }

    def abstract customStages(Map config)

    def prepare(Map config) {}

    def scm(Map config) {
        this.configGit()

        String scmCredentialsId = ""
        if ((Map) config.scm != null) {
            steps.echo "Use scm from config"
            scmCredentialsId = config.scm.credentialsId
            try {
                steps.checkout([$class           : 'GitSCM',
                                branches         : [[name: config.scm.branch]],
                                userRemoteConfigs: [[name: config.scm.repoName, url: config.scm.repoUrl, credentialsId: scmCredentialsId]],
                                extensions       : [[$class: 'CloneOption', noTags: false, shallow: false, timeout: 120],
                                                    [$class: 'CheckoutOption', timeout: 120],
                                                    [$class: 'CleanBeforeCheckout', deleteUntrackedNestedRepositories: false],
                                                    steps.pruneTags(true)
                                ]])
            } catch (Exception ignored) {
                steps.checkout([$class           : 'GitSCM',
                                branches         : [[name: config.scm.branch]],
                                userRemoteConfigs: [[name: config.scm.repoName, url: config.scm.repoUrl, credentialsId: scmCredentialsId]],
                                extensions       : [[$class: 'CloneOption', noTags: false, shallow: false, timeout: 120],
                                                    [$class: 'CheckoutOption', timeout: 120],
                                                    [$class: 'CleanBeforeCheckout', deleteUntrackedNestedRepositories: false],
                                                    steps.pruneTags(true)
                                ]])
            }
        } else {
            steps.echo "Use scm where Jenkinsfile is"
            scmCredentialsId = steps.scm.getUserRemoteConfigs().credentialsId.get(0)
            try {
                steps.checkout([$class           : 'GitSCM',
                                branches         : steps.scm.getBranches(),
                                userRemoteConfigs: steps.scm.getUserRemoteConfigs(),
                                extensions       : [[$class: 'CloneOption', noTags: false, shallow: false, timeout: 120],
                                                    [$class: 'CheckoutOption', timeout: 120],
                                                    [$class: 'CleanBeforeCheckout', deleteUntrackedNestedRepositories: false],
                                                    steps.pruneTags(true)
                                ]])
            } catch (Exception ignored) {
                steps.checkout([$class           : 'GitSCM',
                                branches         : steps.scm.getBranches(),
                                userRemoteConfigs: steps.scm.getUserRemoteConfigs(),
                                extensions       : [[$class: 'CloneOption', noTags: false, shallow: false, timeout: 120],
                                                    [$class: 'CheckoutOption', timeout: 120],
                                                    [$class: 'CleanBeforeCheckout', deleteUntrackedNestedRepositories: false],
                                                    steps.pruneTags(true)
                                ]])
            }
        }

        // Checkout additional scm(s)
        if (config.additionalScm != null) {
            steps.echo "Clone additional scm"
            config.additionalScm.each {
                steps.dir(it.repoName) {
                    this.configGit()

                    if (it.branch == null) {
                        it.branch = Utils.getBranchName(steps)
                    }
                    try {
                        steps.checkout([$class           : 'GitSCM',
                                        branches         : [[name: it.branch]],
                                        userRemoteConfigs: [[name: it.repoName, url: it.repoUrl, credentialsId: scmCredentialsId]],
                                        extensions       : [[$class: 'CloneOption', noTags: false, shallow: false, timeout: 120],
                                                            [$class: 'CheckoutOption', timeout: 120],
                                                            [$class: 'CleanBeforeCheckout', deleteUntrackedNestedRepositories: false],
                                                            steps.pruneTags(true)
                                        ]])
                    } catch (Exception ignored) {
                        steps.checkout([$class           : 'GitSCM',
                                        branches         : [[name: it.branch]],
                                        userRemoteConfigs: [[name: it.repoName, url: it.repoUrl, credentialsId: scmCredentialsId]],
                                        extensions       : [[$class: 'CloneOption', noTags: false, shallow: false, timeout: 120],
                                                            [$class: 'CheckoutOption', timeout: 120],
                                                            [$class: 'CleanBeforeCheckout', deleteUntrackedNestedRepositories: false],
                                                            steps.pruneTags(true)
                                        ]])
                    }
                }
            }
        }
    }

    def configGit() {
        if (steps.isUnix()) {
            steps.sh """
                git config --local core.abbrev 8
                git config --local http.connecttimeout 120
                git config --local core.longpaths true
                git config --local core.autocrlf true
                git config --local --unset credential.helper || :
                find .git -type f -name "*.lock" -delete > /dev/null 2>&1 || :
                rm -fr .git/rebase-apply > /dev/null 2>&1 || :
                rm -fr .git/rebase-merge > /dev/null 2>&1 || :
            """
        } else {
            steps.powershell """
                \$ErrorActionPreference = 'Stop'
                git config --local core.abbrev 8
                git config --local http.connecttimeout 120
                git config --local core.longpaths true
                git config --local core.autocrlf true
                git config --local --unset credential.helper
                Get-ChildItem -Path '.git/*.lock' -Recurse -Force -ErrorAction Ignore | Remove-item -Force
                Remove-Item -Recurse -Force -ErrorAction Ignore .git/rebase-apply
                Remove-Item -Recurse -Force -ErrorAction Ignore .git/rebase-merge
            """
        }
    }

    def build(Map config) {}

    def notify(Map config) {}

    def stopCurrentJob() {
        steps.echo "Stop all running build of the same job..."
        try {
            def jobName = steps.currentBuild.fullProjectName
            def buildNum = steps.currentBuild.number
            def job = Jenkins.instance.getItemByFullName(jobName)
            for (build in job.builds) {
                if (build.isBuilding() && buildNum != build.getNumber().toInteger()) {
                    build.doStop()
                }
            }
        } catch (Exception e) {
        }
    }
}

