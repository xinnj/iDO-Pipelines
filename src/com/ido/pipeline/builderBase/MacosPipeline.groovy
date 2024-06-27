package com.ido.pipeline.builderBase

import com.ido.pipeline.Utils
import com.ido.pipeline.base.BuildPipeline

/**
 * @author xinnj
 */
abstract class MacosPipeline extends BuildPipeline {
    MacosPipeline(Object steps) {
        super(steps)
    }

    @Override
    Map runPipeline(Map config) {
        steps.withCredentials([steps.usernamePassword(credentialsId: config.macos.loginCredentialId, passwordVariable: 'password', usernameVariable: 'username')]) {
            if (config.macos.useRemoteBuilder) {
                steps.lock(label: config.macos.remoteBuilderTags, quantity: 1, resourceSelectStrategy: "random", variable: "builder") {
                    String remoteHost = steps.env.builder0_host
                    String remotePort = steps.env.builder0_port
                    steps.echo "Locked remote builder: " + steps.env.builder

                    config.podTemplate = steps.libraryResource(resource: "pod-template/macos-remote-builder.yaml", encoding: 'UTF-8')
                            .replaceAll('<builderImage>', config._system.macos.remoteBuilderImage)
                            .replaceAll('<REMOTE_HOST>', remoteHost)
                            .replaceAll('<REMOTE_PORT>', remotePort)
                }
            } else {
                config.podTemplate = steps.libraryResource(resource: "pod-template/macos-builder.yaml", encoding: 'UTF-8')
                        .replaceAll('<builderImage>', config._system.macos.builderImage)
                        .replaceAll('<macosImage>', config.macos.macosImage)
            }

            String username = steps.env.username
            String password = steps.env.password
            config.podTemplate = config.podTemplate
                    .replaceAll('<USERNAME>', username)
                    .replaceAll('<PASSWORD>', password)
        }

        return super.runPipeline(config)
    }

    @Override
    def prepare() {
        super.prepare()

        String smbServerAddress
        steps.container('builder') {
            if (config.macos.useRemoteBuilder) {
                smbServerAddress = "//${config._system.smbServer.user}:${config._system.smbServer.password}@${config._system.smbServer.external}/${config._system.smbServer.shareName}"
                steps.sh """${config.debugSh}
                    currentHome=\$HOME
                    sudo -- sh -c "cat \${currentHome}/hosts >> /etc/hosts"
                """
            } else {
                smbServerAddress = "//${config._system.smbServer.user}:${config._system.smbServer.password}@${config._system.smbServer.internal}/${config._system.smbServer.shareName}"
                steps.sh """${config.debugSh}
                    if [[ \$(grep -E -c '(svm|vmx)' /proc/cpuinfo) -le 0 ]]; then
                        echo KVM not possible on this host
                        exit 1
                    fi
                    
                    sudo -- sh -c "echo '127.0.0.1 remote-host' >> /etc/hosts"
                """
            }

            String useCocoapods = (config.macos.useCocoapods as Boolean).toString().toLowerCase()
            steps.sh """${config.debugSh}
                ssh -q remote-host /bin/sh <<EOF
                    ${config.debugSh}
                    set -euao pipefail

                    mkdir -p ~/agent
                    umount ~/agent || true
                    mount -t smbfs ${smbServerAddress} ~/agent

                    mkdir -p \${SPM_CACHE_DIR}
                    mkdir -p \${CP_HOME_DIR}

                    if [ "${useCocoapods}" = "true" ]; then
                        export LANG=en_US.UTF-8
                        export CP_HOME_DIR=\${CP_HOME_DIR}
                        if [ ! -d "\${CP_HOME_DIR}/repos/trunk" ]; then
                            /usr/local/bin/pod --version
                            mkdir -p "\${CP_HOME_DIR}/repos"
                            cd "\${CP_HOME_DIR}/repos"
                            git clone ${config._system.macos.cocoapodsRepoUrl} trunk
                            /usr/local/bin/pod setup
                        fi
                        /usr/local/bin/pod install
                    fi
EOF
            """
        }
    }
}
