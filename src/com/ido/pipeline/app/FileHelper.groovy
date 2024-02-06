package com.ido.pipeline.app

import com.ido.pipeline.Utils

/**
 * @author xinnj
 */
public class FileHelper {
    def steps
    Map config

    FileHelper(Object steps, Map config) {
        this.steps = steps
        this.config = config
    }

    String getFileName() {
        String branch = Utils.getBranchName(steps)
        return "${config.productName}-${branch}-${config.version}"
    }

    def upload(String server, String path) {
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