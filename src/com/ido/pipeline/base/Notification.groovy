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
                (config.notifications as List).each {
                    switch (it) {
                        case "email":
                            String stdReceiver, failureReceiver
                            if (steps.env[config.notificationEmailStdReceiverVar] != null) {
                                stdReceiver = steps.env[config.notificationEmailStdReceiverVar]
                            } else {
                                stdReceiver = config.notificationEmailStdReceiver
                            }
                            if (steps.env[config.notificationEmailFailureReceiverVar] != null) {
                                failureReceiver = steps.env[config.notificationEmailFailureReceiverVar]
                            } else {
                                failureReceiver = config.notificationEmailFailureReceiver
                            }
                            steps.echo "email-stdReceiver: " + stdReceiver
                            steps.echo "email-failureReceiver: " + failureReceiver

                            switch (result) {
                                case "SUCCESS":
                                    if (stdReceiver) {
                                        if (!config.notificationFailureOnly) {
                                            steps.emailext body: '''${SCRIPT, template="groovy-html.template"}''',
                                                    to: "${stdReceiver}",
                                                    subject: "${steps.currentBuild.result}: ${steps.env.JOB_NAME} [${steps.env.BUILD_DISPLAY_NAME}]",
                                                    recipientProviders: [steps.developers(), steps.requestor(), steps.culprits()]
                                        }
                                    }
                                    break;
                                case "FAILURE":
                                    if (failureReceiver) {
                                        steps.emailext body: '''${SCRIPT, template="groovy-html.template"}''',
                                                to: "${failureReceiver}",
                                                subject: "${steps.currentBuild.result}: ${steps.env.JOB_NAME} [${steps.env.BUILD_DISPLAY_NAME}]",
                                                recipientProviders: [steps.developers(), steps.requestor(), steps.culprits()]
                                    }

                                    if (config.notificationSendFailureToStd
                                            && stdReceiver
                                            && stdReceiver != failureReceiver) {
                                        steps.emailext body: '''${SCRIPT, template="groovy-html.template"}''',
                                                to: "${stdReceiver}",
                                                subject: "${steps.currentBuild.result}: ${steps.env.JOB_NAME} [${steps.env.BUILD_DISPLAY_NAME}]",
                                                recipientProviders: [steps.developers(), steps.requestor(), steps.culprits()]
                                    }
                                    break;
                            }
                            break;
                        case "slack":
                            String stdReceiver, failureReceiver
                            if (steps.env[config.notificationSlackStdReceiverVar] != null) {
                                stdReceiver = steps.env[config.notificationSlackStdReceiverVar]
                            } else {
                                stdReceiver = config.notificationSlackStdReceiver
                            }
                            if (steps.env[config.notificationSlackFailureReceiverVar] != null) {
                                failureReceiver = steps.env[config.notificationSlackFailureReceiverVar]
                            } else {
                                failureReceiver = config.notificationSlackFailureReceiver
                            }
                            steps.echo "slack-stdReceiver: " + stdReceiver
                            steps.echo "slack-failureReceiver: " + failureReceiver

                            switch (result) {
                                case "SUCCESS":
                                    if (stdReceiver) {
                                        if (!config.notificationFailureOnly) {
                                            steps.slackSend channel: stdReceiver, color: "good",
                                                    message: "${result}: ${steps.env.JOB_NAME} [${steps.env.BUILD_DISPLAY_NAME}]: (<${steps.env.BUILD_URL}console|Open>)"
                                        }
                                    }
                                    break;
                                case "FAILURE":
                                    if (failureReceiver) {
                                        steps.slackSend channel: failureReceiver, color: "danger",
                                                message: "${result}: ${steps.env.JOB_NAME} [${steps.env.BUILD_DISPLAY_NAME}]: (<${steps.env.BUILD_URL}console|Open>)"
                                    }

                                    if (config.notificationSendFailureToStd
                                            && stdReceiver
                                            && stdReceiver != failureReceiver) {
                                        steps.slackSend channel: stdReceiver, color: "danger",
                                                message: "${result}: ${steps.env.JOB_NAME} [${steps.env.BUILD_DISPLAY_NAME}]: (<${steps.env.BUILD_URL}console|Open>)"
                                    }
                                    break;
                            }
                            break;
                        case "dingtalk":
                            String stdReceiver, failureReceiver
                            if (steps.env[config.notificationDingtalkStdReceiverVar] != null) {
                                stdReceiver = steps.env[config.notificationDingtalkStdReceiverVar]
                            } else {
                                stdReceiver = config.notificationDingtalkStdReceiver
                            }
                            if (steps.env[config.notificationDingtalkFailureReceiverVar] != null) {
                                failureReceiver = steps.env[config.notificationDingtalkFailureReceiverVar]
                            } else {
                                failureReceiver = config.notificationDingtalkFailureReceiver
                            }
                            steps.echo "dingtalk-stdReceiver: " + stdReceiver
                            steps.echo "dingtalk-failureReceiver: " + failureReceiver

                            switch (result) {
                                case "SUCCESS":
                                    if (stdReceiver) {
                                        if (!config.notificationFailureOnly) {
                                            this.sendDingtalk(stdReceiver, 'Success')
                                        }
                                    }
                                    break;
                                case "FAILURE":
                                    if (failureReceiver) {
                                        this.sendDingtalk(failureReceiver, 'Failure')
                                    }

                                    if (config.notificationSendFailureToStd
                                            && stdReceiver
                                            && stdReceiver != failureReceiver) {
                                        this.sendDingtalk(stdReceiver, 'Failure')
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
