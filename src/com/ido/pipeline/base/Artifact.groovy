package com.ido.pipeline.base

/**
 * @author xinnj
 */
class Artifact {
    def uploadToFileServer(Object steps, String server, String rootFolder) {
        steps.dir(rootFolder) {
            def files = steps.findFiles(glob: '**')
            String fileDirectory, filePath
            for (item in files) {
                fileDirectory = item.path.minus(item.name)
                filePath = item.path
                steps.echo "Upload file: ${filePath}"
                steps.httpRequest(httpMode: 'POST', responseHandle: 'NONE', uploadFile: "${filePath}",
                        url: "${server}/${fileDirectory}", multipartName: "${item.name}")
            }
        }
    }
}
