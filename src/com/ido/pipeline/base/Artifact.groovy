package com.ido.pipeline.base

import com.ido.pipeline.Utils

/**
 * @author xinnj
 */
class Artifact {
    def uploadToFileServer(Object steps, String server, String path) {
        if (Utils.isDirectory(steps, path)) {
            steps.dir(path) {
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
        } else {
            String fileName = path.split('/').last()
            steps.echo "Upload file: ${path}"
            steps.httpRequest(httpMode: 'POST', responseHandle: 'NONE', uploadFile: "${path}",
                    url: "${server}", multipartName: "${fileName}")
        }
    }
}
