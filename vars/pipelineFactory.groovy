Map call(Map config) {
    def pipeline = null

    def clsLoader = this.getClass().classLoader
    clsLoader.addClasspath("../src/com.ido.pipeline/")
    pipeline = clsLoader.loadClass(config.pipelineType, true, false)?.newInstance()

    if (pipeline != null) {
        echo "########## Start Pipeline ##########"
        return pipeline.runPipeline(Utils.setDefault(config, this))
    }
}
