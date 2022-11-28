@NonCPS
Map call(Map config) {
    def pipeline = null

    def clsLoader = this.getClass().classLoader
    String scriptDir = new File(getClass().protectionDomain.codeSource.location.path).parent

//    File file = new File(scriptDir + "/t.sh")
//    file.write "export w='hello'\n"
//    file << "echo \$w\n"
//    command = "bash " + scriptDir + "/t.sh"
//    echo command
//    echo command.execute().text

    clsLoader.addClasspath(scriptDir + "/../src/com/ido/pipeline/")
    pipeline = clsLoader.loadClass(config.pipelineType, true, false)?.newInstance(this)

    if (pipeline != null) {
        echo "########## Start Pipeline ##########"
        return pipeline.runPipeline(config)
    }
}
