package com.ido.pipeline.base

import groovy.json.JsonOutput
import groovy.json.JsonSlurperClassic
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

    Map runPipeline(Map originConf) {
        this.config = originConf
        def result = [:]

        if (config.debug) {
            config.put("debugSh", "set -x")
            config.put("debugPowershell", "Set-PSDebug -Trace 1")
        } else {
            config.put("debugSh", "set +x")
            config.put("debugPowershell", "Set-PSDebug -Trace 0")
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

                    steps.podTemplate(yaml: config.podTemplate,
                            idleMinutes: config.keepBuilderPodMinutes,
                            workspaceVolume: workspaceVolumeType(config._system.workspaceVolume.type),
                            slaveConnectTimeout: "3600",
                            showRawYaml: config._system.showRawYaml
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
        }

        result.put("imageTag", config.version)

        return result
    }

    String SubstEnv(String str, Map env) {
        String result = str

        def matcher = str =~ /\$\{(IDO_.+?)\}/
        matcher.each {
            result = result.replace(it[0], env["${it[1]}"])
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

        if (config.dependOn.size() != 0) {
            String upstreamProjects = ""
            String defaultBranch = Utils.getBranchName(steps)

            for (Map job in config.dependOn) {
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
            steps.powershell """${config.debugPowershell}
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

    def afterScm() {
        steps.container('builder') {
            steps.withEnv(["CI_PRODUCTNAME=$config.productName",
                           "CI_BRANCH=" + Utils.getBranchName(steps)]) {
                if (steps.isUnix()) {
                    steps.sh """
                        cd "${config.srcRootPath}"
                        sh "${config.customerBuildScript.afterScm}"
                    """
                } else {
                    steps.powershell """
                        cd "${config.srcRootPath}"
                        "Invoke-Command -ScriptBlock ([ScriptBlock]::Create((Get-Content ${config.customerBuildScript.afterScm})))"
                    """
                }
            }
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
        steps.sh """${config.debugSh}
            mkdir -p "${config.srcRootPath}/ido-cluster"
            echo ${config.version} > "${config.srcRootPath}/ido-cluster/_version"
        """
    }

    def afterVersioning() {
        steps.container('builder') {
            steps.withEnv(["CI_PRODUCTNAME=$config.productName",
                           "CI_VERSION=$config.version",
                           "CI_BRANCH=" + Utils.getBranchName(steps)]) {
                if (steps.isUnix()) {
                    steps.sh """
                        cd "${config.srcRootPath}"
                        sh "${config.customerBuildScript.afterVersioning}"
                    """
                } else {
                    steps.powershell """
                        cd "${config.srcRootPath}"
                        "Invoke-Command -ScriptBlock ([ScriptBlock]::Create((Get-Content ${config.customerBuildScript.afterVersioning})))"
                    """
                }
            }

            if (steps.fileExists("${config.srcRootPath}/ido-cluster/_version")) {
                config.version = steps.readFile(file: "${config.srcRootPath}/ido-cluster/_version", encoding: "UTF-8").trim()
            }
        }
    }

    def abstract ut()

    def abstract codeAnalysis()

    def beforeBuild() {
        steps.container('builder') {
            steps.withEnv(["CI_PRODUCTNAME=$config.productName",
                           "CI_VERSION=$config.version",
                           "CI_BRANCH=" + Utils.getBranchName(steps)]) {
                if (steps.isUnix()) {
                    steps.sh """
                        cd "${config.srcRootPath}"
                        sh "${config.customerBuildScript.beforeBuild}"
                    """
                } else {
                    steps.powershell """
                        cd "${config.srcRootPath}"
                        "Invoke-Command -ScriptBlock ([ScriptBlock]::Create((Get-Content ${config.customerBuildScript.beforeBuild})))"
                    """
                }
            }
        }
    }

    def abstract build()

    Boolean customerBuild() {
        Boolean runCustomer
        steps.container('builder') {
            if (steps.fileExists("${steps.WORKSPACE}/${config.srcRootPath}/${config.customerBuildScript.build}")) {
                steps.echo "Execute customer build script: ${config.customerBuildScript.build}"
                steps.withEnv(["CI_PRODUCTNAME=$config.productName",
                               "CI_VERSION=$config.version",
                               "CI_BRANCH=" + Utils.getBranchName(steps)]) {
                    if (steps.isUnix()) {
                        steps.sh """
                            cd "${config.srcRootPath}" 
                            sh "./${config.customerBuildScript.build}"
                        """
                    } else {
                        steps.powershell """
                            cd "${config.srcRootPath}"
                            "Invoke-Command -ScriptBlock ([ScriptBlock]::Create((Get-Content ${config.customerBuildScript.build})))"
                        """
                    }
                }
                runCustomer = true
            } else {
                runCustomer = false
            }
        }
        return runCustomer
    }

    def afterBuild() {
        steps.container('builder') {
            steps.withEnv(["CI_PRODUCTNAME=$config.productName",
                           "CI_VERSION=$config.version",
                           "CI_BRANCH=" + Utils.getBranchName(steps)]) {
                if (steps.isUnix()) {
                    steps.sh """
                        cd "${config.srcRootPath}"
                        sh "${config.customerBuildScript.afterBuild}"
                    """
                } else {
                    steps.powershell """
                        cd "${config.srcRootPath}"
                        "Invoke-Command -ScriptBlock ([ScriptBlock]::Create((Get-Content ${config.customerBuildScript.afterBuild})))"
                    """
                }
            }
        }
    }

    def abstract archive()

    def afterArchive() {
        steps.container('builder') {
            steps.withEnv(["CI_PRODUCTNAME=$config.productName",
                           "CI_VERSION=$config.version",
                           "CI_BRANCH=" + Utils.getBranchName(steps)]) {
                if (steps.isUnix()) {
                    steps.sh """
                        cd "${config.srcRootPath}"
                        sh "${config.customerBuildScript.afterArchive}"
                    """
                } else {
                    steps.powershell """
                        cd "${config.srcRootPath}"
                        "Invoke-Command -ScriptBlock ([ScriptBlock]::Create((Get-Content ${config.customerBuildScript.afterArchive})))"
                    """
                }
            }
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
        String defaultBranch = Utils.getBranchName(steps)

        for (Map job in config.jobsInvoked) {
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
            String defaultBranch = Utils.getBranchName(steps)

            def item
            for (Map job in config.jobsInvoked) {
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
}

