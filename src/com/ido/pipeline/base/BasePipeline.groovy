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
abstract class BasePipeline implements Pipeline, Serializable {
    def steps
    Map config
    String fastStop = ""

    BasePipeline(Object steps) {
        this.steps = steps
    }

    Map runPipeline(Map config) {
        this.config = config
        def result = [:]

        if (config.stopAllRunningBuild) {
            // Stop all running build of the same job
            this.stopCurrentJob()
        }

        steps.node('master') {}

        switch (config.nodeType) {
            case "standalone":
                steps.node(config.nodeName) {
                    try {
                        customStages()
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
                        this.sendNotification()
                    }
                }
                break
            case "k8s":
                def podRetentionType = { Boolean k ->
                    if (k) {
                        steps.always()
                    } else {
                        steps.never()
                    }
                }

                def workspaceVolumeType = { String type ->
                    switch (type) {
                        case "hostPath":
                            return steps.hostPathWorkspaceVolume(hostPath: config._system.workspaceVolume.hostPath)
                            break
                        case "nfs":
                            return steps.nfsWorkspaceVolume(serverAddress: config._system.workspaceVolume.nfsServerAddress,
                                    serverPath: config._system.workspaceVolume.nfsServerPath)
                            break
                        case "pvc":
                            return steps.persistentVolumeClaimWorkspaceVolume(claimName: config._system.workspaceVolume.pvcName)
                            break
                        default:
                            steps.error "Wrong workspaceVolumeType: ${type}"
                    }
                }

                steps.podTemplate(yaml: config.podTemplate,
                        podRetention: podRetentionType(config.keepBuilderPod),
                        workspaceVolume: workspaceVolumeType(config._system.workspaceVolume.type)
                ) {
                    steps.node(steps.POD_LABEL) {
                        try {
                            customStages()
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
                            this.sendNotification()
                        }
                    }
                }
                podRetentionType = null
                workspaceVolumeType = null
                break
            default:
                steps.error "Node type: ${config.nodeType} is not supported!"
        }
        return result
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

        steps.stage('Versioning') {
            steps.echo "########## Stage: Versioning ##########"
            this.versioning()
        }

        steps.stage('Build') {
            steps.echo "########## Stage: Build ##########"
            this.build()
        }
    }

    def prepare() {
        String upstreamProjects = ""
        String branch = Utils.getBranchName(steps)

        for (Map job in config.dependOn) {
            if (job.name) {
                if (job.branch) {
                    if (upstreamProjects == "") {
                        upstreamProjects = job.name + '/' + branch
                    } else {
                        upstreamProjects += ',' + job.name + '/' + branch
                    }
                } else {
                    if (upstreamProjects == "") {
                        upstreamProjects = job.name + '/' + job.branch
                    } else {
                        upstreamProjects += ',' + job.name + '/' + job.branch
                    }
                }
            }
        }

        steps.echo "upstreamProjects: " + upstreamProjects
        if (upstreamProjects != "") {
            steps.properties([
                    steps.pipelineTriggers([steps.upstream(upstreamProjects: upstreamProjects, threshold: hudson.model.Result.SUCCESS)])
            ])
        }
    }

    def scm() {
        this.configGit()

        String scmCredentialsId = ""
        if ((Map) config.scm != null) {
            steps.echo "Use scm from config"
            scmCredentialsId = config.scm.credentialsId
            try {
                steps.checkout([$class           : 'GitSCM',
                                branches         : [[name: config.scm.branch]],
                                userRemoteConfigs: [[name: "origin", url: config.scm.repoUrl, credentialsId: scmCredentialsId]],
                                extensions       : [[$class: 'CloneOption', noTags: false, shallow: false, timeout: 120],
                                                    [$class: 'CheckoutOption', timeout: 120],
                                                    [$class: 'CleanBeforeCheckout', deleteUntrackedNestedRepositories: false],
                                                    steps.pruneTags(true)
                                ]])
            } catch (Exception ignored) {
                steps.checkout([$class           : 'GitSCM',
                                branches         : [[name: config.scm.branch]],
                                userRemoteConfigs: [[name: "origin", url: config.scm.repoUrl, credentialsId: scmCredentialsId]],
                                extensions       : [[$class: 'CloneOption', noTags: false, shallow: false, timeout: 120],
                                                    [$class: 'CheckoutOption', timeout: 120],
                                                    [$class: 'CleanBeforeCheckout', deleteUntrackedNestedRepositories: false],
                                                    steps.pruneTags(true)
                                ]])
            }
        } else if (steps.scm != null) {
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

                    if (!it.branch) {
                        it.branch = Utils.getBranchName(steps)
                    }

                    if (!it.credentialsId) {
                        scmCredentialsId = it.credentialsId
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
                git config --global core.abbrev 8
                git config --global http.connecttimeout 120
                git config --global core.longpaths true
                git config --global core.autocrlf false
                git config --global --unset credential.helper || :
                find .git -type f -name "*.lock" -delete > /dev/null 2>&1 || :
                rm -fr .git/rebase-apply > /dev/null 2>&1 || :
                rm -fr .git/rebase-merge > /dev/null 2>&1 || :
            """
        } else {
            steps.powershell """
                \$ErrorActionPreference = 'Stop'
                Set-PSDebug -Trace 1
                
                git config --global core.abbrev 8
                git config --global http.connecttimeout 120
                git config --global core.longpaths true
                git config --global core.autocrlf false
                git config --global --unset credential.helper
                Get-ChildItem -Path '.git/*.lock' -Recurse -Force -ErrorAction Ignore | Remove-item -Force
                Remove-Item -Recurse -Force -ErrorAction Ignore .git/rebase-apply
                Remove-Item -Recurse -Force -ErrorAction Ignore .git/rebase-merge
                exit 0
            """
        }
    }

    def versioning() {
        if (!config.version) {
            Version verObj = new Version()
            String ver = verObj.getVersion(steps, config)
            steps.echo "version: " + ver
            config.version = ver
        }
        steps.currentBuild.displayName = config.version
    }

    def abstract build()

    def sendNotification() {
        if (steps.fileExists('_finalVersion')) {
            steps.currentBuild.displayName = steps.readFile(file: '_finalVersion', encoding: "UTF-8").trim()
        }

        Notification notification = new Notification(steps, config)
        notification.send()
    }

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

