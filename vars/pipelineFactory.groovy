@NonCPS
Map call(Map config) {
    def pipeline = null

    def clsLoader = this.getClass().classLoader
    String scriptDir = new File(getClass().protectionDomain.codeSource.location.path).parent
    clsLoader.addClasspath(scriptDir + "/../src/com/ido/pipeline/")
    pipeline = clsLoader.loadClass(config.pipelineType, true, false)?.newInstance(this)

    if (pipeline != null) {
        echo "########## Start Pipeline ##########"
        return pipeline.runPipeline(config)
    }
}
