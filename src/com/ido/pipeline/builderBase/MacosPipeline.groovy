package com.ido.pipeline.builderBase

import com.ido.pipeline.base.BuildPipeline

/**
 * @author xinnj
 */
abstract class MacosPipeline extends BuildPipeline {
    MacosPipeline(Object steps) {
        super(steps)
    }

    @Override
    def prepare() {
        super.prepare()

        if (config.macos.useCocoapods) {
            steps.sh """${config.debugSh}
                export LANG=en_US.UTF-8
                if [ ! -d "~/.cocoapods/repos/trunk" ]; then
                    /usr/local/bin/pod --version
                    mkdir -p "~/.cocoapods/repos"
                    cd "~/.cocoapods/repos"
                    git config --global http.postBuffer 524288000
                    git clone ${config._system.macos.cocoapodsRepoUrl} trunk
                    /usr/local/bin/pod setup
                fi
            """
        }
    }

    @Override
    def scm() {
        super.scm()

        if (config.macos.useCocoapods) {
            steps.sh """${config.debugSh}
                cd "${steps.WORKSPACE}/${config.srcRootPath}"
                /usr/local/bin/pod install
            """
        }
    }
}
