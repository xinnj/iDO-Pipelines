package com.ido.pipeline

import com.ido.pipeline.base.BasePipeline

/**
 * @author xinnj
 */
class GenericPipeline extends BasePipeline {
    GenericPipeline(Object steps) {
        super(steps)
    }

    def build() {
        if (steps.isUnix()) {
            steps.sh '''
                echo "hello world"
            '''
        } else {
            steps.powershell '''
                echo "hello world"
            '''
        }
    }
}