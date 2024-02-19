package com.ido.pipeline

import com.ido.pipeline.base.BasePipeline

/**
 * @author xinnj
 */
class GenericPipeline extends BasePipeline {
    GenericPipeline(Object steps) {
        super(steps)
    }

    @Override
    def ut() {
        return null
    }

    @Override
    def codeAnalysis() {
        return null
    }

    def build() {
        steps.container('builder') {
            steps.withEnv(["CI_PRODUCTNAME=$config.productName",
                           "CI_VERSION=$config.version",
                           "CI_BRANCH=" + Utils.getBranchName(steps)]) {
                if (steps.isUnix()) {
                    steps.sh """${config.debugSh}
                        cd "${config.srcRootPath}"
                        sh build.sh
                    """
                } else {
                    steps.powershell """${config.debugPowershell}
                        cd "${config.srcRootPath}"
                        build.ps1
                    """
                }
            }
        }
    }

    @Override
    def archive() {
        return null
    }
}