package com.ido.pipeline.base

import com.ido.pipeline.Utils

class Notification {
    Object steps
    Map config

    Notification(Object steps, Map config) {
        this.steps = steps
        this.config = config
    }

    def send() {
        steps.withFolderProperties {
            String result = (steps.currentBuild.result as String).toUpperCase()
            try {
                (config.notification.type as List).each {
                    switch (it) {
                        case "email":
                            String successReceiver, failureReceiver
                            if (steps.env[config.notification.email.successReceiverFolderProperty]) {
                                successReceiver = steps.env[config.notification.email.successReceiverFolderProperty]
                            } else {
                                successReceiver = config.notification.email.successReceiver
                            }
                            if (steps.env[config.notification.email.failureReceiverFolderProperty]) {
                                failureReceiver = steps.env[config.notification.email.failureReceiverFolderProperty]
                            } else {
                                failureReceiver = config.notification.email.failureReceiver
                            }
                            steps.echo "email-successReceiver: " + successReceiver
                            steps.echo "email-failureReceiver: " + failureReceiver

                            switch (result) {
                                case "SUCCESS":
                                    if (!config.notification.failureOnly) {
                                        steps.emailext body: '''${SCRIPT, template="groovy-html.template"}''',
                                                to: "${successReceiver}",
                                                subject: "${steps.currentBuild.result}: ${steps.env.JOB_NAME} [${steps.env.BUILD_DISPLAY_NAME}]",
                                                recipientProviders: [steps.developers(), steps.requestor(), steps.culprits()]
                                    }
                                    break;
                                case "FAILURE":
                                    if (config.notification.sendFailureToSuccess
                                            && successReceiver
                                            && successReceiver != failureReceiver) {
                                        failureReceiver = failureReceiver + ', ' + successReceiver
                                    }

                                    steps.emailext body: '''${SCRIPT, template="groovy-html.template"}''',
                                            to: "${failureReceiver}",
                                            subject: "${steps.currentBuild.result}: ${steps.env.JOB_NAME} [${steps.env.BUILD_DISPLAY_NAME}]",
                                            recipientProviders: [steps.developers(), steps.requestor(), steps.culprits()]
                                    break;
                            }
                            break;
                        case "slack":
                            String successReceiver, failureReceiver
                            if (steps.env[config.notification.slack.successReceiverFolderProperty]) {
                                successReceiver = steps.env[config.notification.slack.successReceiverFolderProperty]
                            } else {
                                successReceiver = config.notification.slack.successReceiver
                            }
                            if (steps.env[config.notification.slack.failureReceiverFolderProperty]) {
                                failureReceiver = steps.env[config.notification.slack.failureReceiverFolderProperty]
                            } else {
                                failureReceiver = config.notification.slack.failureReceiver
                            }
                            steps.echo "slack-successReceiver: " + successReceiver
                            steps.echo "slack-failureReceiver: " + failureReceiver

                            switch (result) {
                                case "SUCCESS":
                                    if (successReceiver) {
                                        if (!config.notification.failureOnly) {
                                            steps.slackSend channel: successReceiver, color: "good",
                                                    message: "${result}: ${steps.env.JOB_NAME} [${steps.env.BUILD_DISPLAY_NAME}]: (<${steps.env.BUILD_URL}console|Open>)"
                                        }
                                    }
                                    break;
                                case "FAILURE":
                                    if (failureReceiver) {
                                        steps.slackSend channel: failureReceiver, color: "danger",
                                                message: "${result}: ${steps.env.JOB_NAME} [${steps.env.BUILD_DISPLAY_NAME}]: (<${steps.env.BUILD_URL}console|Open>)"
                                    }

                                    if (config.notification.sendFailureToSuccess
                                            && successReceiver
                                            && successReceiver != failureReceiver) {
                                        steps.slackSend channel: successReceiver, color: "danger",
                                                message: "${result}: ${steps.env.JOB_NAME} [${steps.env.BUILD_DISPLAY_NAME}]: (<${steps.env.BUILD_URL}console|Open>)"
                                    }
                                    break;
                            }
                            break;
                        case "dingtalk":
                            String successReceiver, failureReceiver
                            if (steps.env[config.notification.dingtalk.successReceiverFolderProperty]) {
                                successReceiver = steps.env[config.notification.dingtalk.successReceiverFolderProperty]
                            } else {
                                successReceiver = config.notification.dingtalk.successReceiver
                            }
                            if (steps.env[config.notification.dingtalk.failureReceiverFolderProperty]) {
                                failureReceiver = steps.env[config.notification.dingtalk.failureReceiverFolderProperty]
                            } else {
                                failureReceiver = config.notification.dingtalk.failureReceiver
                            }
                            steps.echo "dingtalk-successReceiver: " + successReceiver
                            steps.echo "dingtalk-failureReceiver: " + failureReceiver

                            switch (result) {
                                case "SUCCESS":
                                    if (successReceiver) {
                                        if (!config.notification.failureOnly) {
                                            this.sendDingtalk(successReceiver, 'Success')
                                        }
                                    }
                                    break;
                                case "FAILURE":
                                    if (failureReceiver) {
                                        this.sendDingtalk(failureReceiver, 'Failure')
                                    }

                                    if (config.notification.sendFailureToSuccess
                                            && successReceiver
                                            && successReceiver != failureReceiver) {
                                        this.sendDingtalk(successReceiver, 'Failure')
                                    }
                                    break;
                            }
                            break;
                    }
                }
            } catch (Exception e) {
                steps.echo 'Exception occurred when notifying: ' + e.toString()
            }
        }
    }

    private sendDingtalk(String robotId, String resultDesc) {
        String startTime = new Date(steps.currentBuild.startTimeInMillis).format("yyyy-MM-dd HH:mm:ss")
        String duration = (steps.currentBuild.durationString as String).split(' and')[0]
        steps.dingtalk(
                robot: robotId,
                type: "MARKDOWN",
                title: "${resultDesc}: ${steps.env.JOB_NAME}",
                text: [
                        "### ${resultDesc}",
                        ">- Job Name: **${steps.env.JOB_NAME}**",
                        ">- Build Version: **${steps.env.BUILD_DISPLAY_NAME}**",
                        ">- Start Time: **${startTime}**",
                        ">- Build Duration: **${duration}**",
                        ">- Build Log: [Click to open](${steps.env.BUILD_URL}console)",
                        "#### Changes:",
                        "${this.getChangeString()}"
                ]
        )
    }

    private String getChangeString() {
        String changeString = ""
        def MAX_MSG_LEN = 20
        def changeLogSets = steps.currentBuild.changeSets
        for (int i = 0; i < changeLogSets.size(); i++) {
            def entries = changeLogSets[i].items
            for (int j = 0; j < entries.length; j++) {
                def entry = entries[j]
                String truncatedMsg = entry.msg.take(MAX_MSG_LEN)
                String commitTime = new Date(entry.timestamp).format("yyyy-MM-dd HH:mm:ss")
                changeString += " - ${truncatedMsg} [${entry.author} ${commitTime}]\n"
            }
        }
        if (!changeString) {
            changeString = " - No new changes"
        }
        return (changeString)
    }
}
