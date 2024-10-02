package com.ido.pipeline

import com.ido.pipeline.base.BuildPipeline

/**
 * @author xinnj
 */
class GenericPipeline extends BuildPipeline {
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
        return null
    }

    @Override
    def archive() {
        return null
    }
}