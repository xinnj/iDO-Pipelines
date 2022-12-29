import com.ido.pipeline.Utils

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

    def m = config.pipelineType =~ /(.*\/)(.*)/;
    String classPath = ""
    String className = ""
    if (m) {
        classPath = m[0][1]
        className = m[0][2]
    } else {
        className = config.pipelineType
    }
    m = null

    clsLoader.addClasspath(scriptDir + "/../src/com/ido/pipeline/" + classPath)
    pipeline = clsLoader.loadClass(className, true, false)?.newInstance(this)
    clsLoader = null

    if (pipeline != null) {
        echo "########## Start Pipeline ##########"
        return pipeline.runPipeline(Utils.setDefault(config, this))
    }
    pipeline = null
}
