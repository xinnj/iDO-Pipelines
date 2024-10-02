package com.ido.pipeline.base

import com.ido.pipeline.Utils
import groovy.json.JsonOutput
import groovy.json.JsonSlurperClassic
import hudson.model.*
import hudson.scm.*
import jenkins.model.*
import org.jenkinsci.plugins.workflow.steps.FlowInterruptedException

import java.util.Date.*
import java.util.TimeZone.*

/**
 * @author xinnj
 */
abstract class BasePipeline implements Pipeline, Serializable {
    def steps
    Map config
    String fastStop = ""
    boolean useK8sAgent = true
    String nodeName = ""

    BasePipeline(Object steps) {
        this.steps = steps
    }

    Map runPipeline(Map originConf) {
        this.config = originConf
        if (this.config.useK8sAgent == null) {
            this.config.useK8sAgent = this.useK8sAgent
        }
        if (!this.config.nodeName) {
            this.config.nodeName = this.nodeName
        }

        def result = [:]

        if (config.debug) {
            config.put("debugSh", "{ set -x; } 2>/dev/null")
            config.put("debugPwsh", "Set-PSDebug -Trace 1")
        } else {
            config.put("debugSh", "{ set +x; } 2>/dev/null")
            config.put("debugPwsh", "Set-PSDebug -Trace 0")
        }

        if (config.stopSameJob) {
            // Stop all running build of the same job
            this.stopSameJob()
        }
        if (config.stopInvokedJob) {
            // Stop all running build of the invoked job
            this.stopInvokedJob()
        }

        steps.lock(resource: "lock_${steps.currentBuild.fullProjectName}") {
            steps.node('Workspace') {
                String json = JsonOutput.toJson(config)

                Map packagesEnv = [:]
                String output = steps.sh(returnStdout: true, encoding: "UTF-8", script: """${config.debugSh}
bash -c "export -p | awk '{print \\\$3'} | grep \\"^IDO_\\" | tr -d '\\"'"
""")
                output.trim()
                        .split(System.lineSeparator())
                        .each { item ->
                            def object = item.split("=")
                            packagesEnv.put(object[0], object[1])
                        }

                def jsonSlurperClassic = new JsonSlurperClassic()
                config = jsonSlurperClassic.parseText(SubstEnv(json, packagesEnv)) as Map
                // steps.echo config.toMapString()
            }

            if (config.useK8sAgent) {
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

                if (config._system.imagePullMirror) {
                    config.podTemplate = Utils.replaceImageMirror(config._system.imageMirrors, config.podTemplate)
                }

                config.podTemplate = (config.podTemplate as String)
                        .replaceAll('<keepBuilderPodMaxMinutes>', (config._system.keepBuilderPodMaxMinutes).toString())
                        .replaceAll('<imagePullSecret>', config._system.imagePullSecret as String)
                        .replaceAll('<inboundAgentImage>', config._system.inboundAgentImage as String)

                // steps.echo config.podTemplate
                steps.podTemplate(yaml: config.podTemplate,
                        idleMinutes: config.keepBuilderPodMinutes,
                        workspaceVolume: workspaceVolumeType(config._system.workspaceVolume.type),
                        slaveConnectTimeout: "3600",
                        showRawYaml: config._system.showRawYaml
                ) {
                    steps.node(steps.POD_LABEL) {
                        runStages()
                    }
                }
                podRetentionType = null
                workspaceVolumeType = null
            } else {
                steps.node(config.nodeName) {
                    runStages()
                }
            }
        }

        result.put("imageTag", config.version)

        return result
    }

    def runStages() {
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
            steps.echo "\033[32m########## Stage: Notify ##########\033[0m"
            if (steps.currentBuild.result == null) {
                steps.currentBuild.result = 'FAILURE'
            }
            this.sendNotification()
        }
    }

    def String SubstEnv(String str, Map env) {
        String result = str

        def matcher = str =~ /\$\{(IDO_.+?)\}/
        matcher.each {
            if (config.debug) {
                steps.echo "var: ${it[0]}"
                steps.echo "env: " + env["${it[1]}"]
            }
            result = result.replace(it[0], env["${it[1]}"])
        }

        return result
    }

    def abstract customStages()

    def prepare() {
        config.branch = Utils.getBranchName(steps)
        steps.env.CI_PRODUCTNAME = config.productName
        steps.env.CI_BRANCH = config.branch

        if (config.dependOn.size() != 0) {
            String upstreamProjects = ""
            String defaultBranch = config.branch

            for (Map job in (config.dependOn as List<Map>)) {
                if (job.name) {
                    if (job.branch) {
                        if (upstreamProjects == "") {
                            upstreamProjects = job.name + '/' + job.branch
                        } else {
                            upstreamProjects += ',' + job.name + '/' + job.branch
                        }
                    } else {
                        if (upstreamProjects == "") {
                            upstreamProjects = job.name + '/' + defaultBranch
                        } else {
                            upstreamProjects += ',' + job.name + '/' + defaultBranch
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
    }

    def scm() {
        this.configGit()

        String scmCredentialsId = ""
        if ((Map) config.scm != null) {
            steps.echo "Use scm from config"
            scmCredentialsId = config.scm.credentialsId
            steps.checkout([$class           : 'GitSCM',
                            branches         : [[name: config.scm.branch]],
                            userRemoteConfigs: [[name: "origin", url: config.scm.repoUrl, credentialsId: scmCredentialsId]],
                            extensions       : [[$class: 'CloneOption', noTags: false, shallow: false, timeout: 120],
                                                [$class: 'CheckoutOption', timeout: 120],
                                                steps.pruneTags(true)
                            ]])
        } else if (steps.scm != null) {
            steps.echo "Use scm where Jenkinsfile is"
            scmCredentialsId = steps.scm.getUserRemoteConfigs().credentialsId.get(0)
            steps.checkout([$class           : 'GitSCM',
                            branches         : steps.scm.getBranches(),
                            userRemoteConfigs: steps.scm.getUserRemoteConfigs(),
                            extensions       : [[$class: 'CloneOption', noTags: false, shallow: false, timeout: 120],
                                                [$class: 'CheckoutOption', timeout: 120],
                                                steps.pruneTags(true)
                            ]])
        }

        this.cleanGit()

        // Checkout additional scm(s)
        if (config.additionalScm != null) {
            steps.echo "Clone additional scm"
            config.additionalScm.each {
                steps.dir(it.repoName) {
                    this.configGit()

                    if (!it.branch) {
                        it.branch = config.branch
                    }

                    if (!it.credentialsId) {
                        scmCredentialsId = it.credentialsId
                    }

                    steps.checkout([$class           : 'GitSCM',
                                    branches         : [[name: it.branch]],
                                    userRemoteConfigs: [[name: it.repoName, url: it.repoUrl, credentialsId: scmCredentialsId]],
                                    extensions       : [[$class: 'CloneOption', noTags: false, shallow: false, timeout: 120],
                                                        [$class: 'CheckoutOption', timeout: 120],
                                                        steps.pruneTags(true)
                                    ]])

                    this.cleanGit()
                }
            }
        }
    }

    def configGit() {
        if (steps.isUnix()) {
            steps.sh """${config.debugSh}
                git config --global core.abbrev 8
                git config --global http.connecttimeout 120
                git config --global core.longpaths true
                git config --global core.autocrlf false
                git config --global pull.rebase true
                git config --global --unset credential.helper || :
                find .git -type f -name "*.lock" -delete > /dev/null 2>&1 || :
                rm -fr .git/rebase-apply > /dev/null 2>&1 || :
                rm -fr .git/rebase-merge > /dev/null 2>&1 || :
            """
        } else {
            steps.pwsh """${config.debugPwsh}
                \$ErrorActionPreference = 'Stop'
                
                git config --global core.abbrev 8
                git config --global http.connecttimeout 120
                git config --global core.longpaths true
                git config --global core.autocrlf false
                git config --global pull.rebase true
                git config --global --unset credential.helper
                Get-ChildItem -Path '.git/*.lock' -Recurse -Force -ErrorAction Ignore | Remove-item -Force
                Remove-Item -Recurse -Force -ErrorAction Ignore .git/rebase-apply
                Remove-Item -Recurse -Force -ErrorAction Ignore .git/rebase-merge
                exit 0
            """
        }
    }

    def cleanGit() {
        String excludeCmd = ""
        if (config.scmCleanExclude) {
            excludeCmd = "-e \"${config.scmCleanExclude}\""
        }

        if (steps.isUnix()) {
            steps.sh """${config.debugSh}
                git reset --hard -q
                git clean -fdxq ${excludeCmd}
            """
        } else {
            steps.pwsh """${config.debugPwsh}
                \$ErrorActionPreference = 'Stop'
                git reset --hard -q
                git clean -fdxq ${excludeCmd}
            """
        }
    }

    def afterScm() {
        runCustomerBuildScript(config.customerBuildScript.afterScm)
    }

    def versioning() {
        if (!config.version) {
            Version verObj = new Version()
            String ver = verObj.getVersion(steps, config)
            steps.echo "version: " + ver
            config.version = ver
        }
        steps.currentBuild.displayName = config.version
        steps.env.CI_VERSION = config.version
    }

    def afterVersioning() {
        runCustomerBuildScript(config.customerBuildScript.afterVersioning)

        if (steps.fileExists("${config.srcRootPath}/ido-cluster/_version")) {
            config.version = steps.readFile(file: "${config.srcRootPath}/ido-cluster/_version", encoding: "UTF-8").trim()
            steps.currentBuild.displayName = config.version
            steps.env.CI_VERSION = config.version
        }
    }

    def sendNotification() {
        if (steps.fileExists("${config.srcRootPath}/ido-cluster/_finalVersion")) {
            steps.currentBuild.displayName = steps.readFile(file: "${config.srcRootPath}/ido-cluster/_finalVersion", encoding: "UTF-8").trim()
        }

        Notification notification = new Notification(steps, config)
        notification.send()
    }

    def stopSameJob() {
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
        } catch (Exception ignored) {
        }
    }

    def invoke(List<?> parameters) {
        String defaultBranch = config.branch

        for (Map job in (config.jobsInvoked as List<Map>)) {
            if (job.name) {
                if (job.branch) {
                    try {
                        if (parameters) {
                            steps.build(job: job.name + '/' + job.branch, parameters: parameters, propagate: false, wait: false)
                        } else {
                            steps.build(job: job.name + '/' + job.branch, propagate: false, wait: false)
                        }
                    } catch (Exception e) {
                        steps.echo e.toString()
                        steps.echo "Can't invoke job: " + job.name + '/' + job.branch
                    }
                } else {
                    try {
                        if (parameters) {
                            steps.build(job: job.name + '/' + defaultBranch, parameters: parameters, propagate: false, wait: false)
                        } else {
                            steps.build(job: job.name + '/' + defaultBranch, propagate: false, wait: false)
                        }
                    } catch (Exception e) {
                        steps.echo e.toString()
                        steps.echo "Can't invoke job: " + job.name + '/' + defaultBranch
                    }
                }
            }
        }
    }

    // Todo: the pod will be created and remain running if the job is stopped before pod created
    def stopInvokedJob() {
        if (config.jobsInvoked.size() != 0) {
            steps.echo "Stop all running build of the jobs to be invoked..."
            String defaultBranch = config.branch

            def item
            for (Map job in (config.jobsInvoked as List<Map>)) {
                if (job.name) {
                    if (job.branch) {
                        try {
                            item = Jenkins.instance.getItemByFullName(job.name + '/' + job.branch)
                            for (build in item.builds) {
                                if (build.isBuilding()) {
                                    build.doStop()
                                }
                            }
                        } catch (Exception ignored) {
                        }
                    } else {
                        try {
                            item = Jenkins.instance.getItemByFullName(job.name + '/' + defaultBranch)
                            for (build in item.builds) {
                                if (build.isBuilding()) {
                                    build.doStop()
                                }
                            }
                        } catch (Exception ignored) {
                        }
                    }
                }
            }
        }
    }

    boolean checkCustomerBuildScript(String script) {
        if (steps.isUnix()) {
            return steps.fileExists("${steps.WORKSPACE}/${config.srcRootPath}/${script}.sh")
        } else {
            return steps.fileExists("${steps.WORKSPACE}/${config.srcRootPath}/${script}.ps1")
        }
    }

    def runCustomerBuildScript(String script) {
        if (config.useK8sAgent) {
            steps.container('builder') {
                steps.sh """${config.debugSh}
                    cd "${config.srcRootPath}"
                    chmod +x "${script}.sh"
                    "${script}.sh"
                """
            }
        } else {
            if (steps.isUnix()) {
                steps.sh """${config.debugSh}
                    cd "${config.srcRootPath}"
                    chmod +x "${script}.sh"
                    "${script}.sh"
                """
            } else {
                steps.pwsh """${config.debugPwsh}
                    \$ErrorActionPreference = 'Stop'
                    cd "${config.srcRootPath}"
                    & "${script}.ps1"
                """
            }
        }
    }

    def execOnAgent(String container, Closure clos) {
        if (config.useK8sAgent && container) {
            steps.container(container) {
                return clos()
            }
        } else {
            return clos()
        }
    }
}

