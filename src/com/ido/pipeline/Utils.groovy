package com.ido.pipeline

import jenkins.model.*
import java.util.Date.*
import java.util.TimeZone.*

/**
 * @author xinnj
 */
public class Utils {
    @SuppressWarnings('GroovyAssignabilityCheck')
    static Map setDefault(Map config, steps) {
        Map defaults = steps.readYaml(text: steps.libraryResource('config/default.yaml'))
        Map system = steps.readYaml(text: steps.libraryResource('config/system.yaml'))
        defaults._system = system
        return deepMerge(defaults, config)
    }

    // Refer to: https://e.printstacktrace.blog/how-to-merge-two-maps-in-groovy/
    static Map deepMerge(Map lhs, Map rhs) {
        return rhs.inject(lhs.clone()) { map, entry ->
            if (map[entry.key] instanceof Map && entry.value instanceof Map) {
                map[entry.key] = deepMerge(map[entry.key], entry.value)
            } else if (map[entry.key] instanceof Collection && entry.value instanceof Collection) {
                map[entry.key] += entry.value
            } else {
                map[entry.key] = entry.value
            }
            return map
        }
    }

    static String getBranchName(steps) {
        steps.echo "BRANCH_NAME: " + steps.env.BRANCH_NAME

        if (steps.env.BRANCH_NAME != null) {
            return steps.env.BRANCH_NAME.split("/")[0]
        }

        return 'null'
    }

    static String getGitCommitVersion(steps) {
        if (steps.isUnix()) {
            steps.sh """
                git describe --always --long > _gitDesc
            """
        } else {
            steps.bat """
                @echo off
                git describe --always --long > _gitDesc
            """
        }
        String[] strArray = (steps.readFile(file: '_gitDesc', encoding: "UTF-8") as String).trim().split('-')
        return strArray[0] + '.' + strArray[1]
    }

    static copyToFolder(Object steps, String source, String destination, boolean cleanDest) {
        if (steps.isUnix()) {
            steps.sh """
                mkdir -p ${destination}
            """
        } else {
            source = source.replaceAll("/", "\\\\")
            destination = destination.replaceAll("/", "\\\\")
            steps.bat """
                @echo off
                if not exist ${destination} md ${destination}
            """
        }

        if (cleanDest) {
            if (steps.isUnix()) {
                steps.sh """
                rm -rf ${destination}/*
            """
            } else {
                source = source.replaceAll("/", "\\\\")
                destination = destination.replaceAll("/", "\\\\")
                steps.bat """
                @echo off
                del /s /f /q ${destination}\\*
            """
            }
        }

        if (steps.isUnix()) {
            steps.sh """
                cp -af ${source} ${destination}
            """
        } else {
            source = source.replaceAll("/", "\\\\")
            destination = destination.replaceAll("/", "\\\\")
            steps.bat """
                @echo off
                xcopy /E /Y ${source} ${destination}
                if not %errorlevel%==0 exit /B 1
            """
        }
    }

    static notarizeMacApp(Object steps, String inputFile, String macBundleId, String macTeamId) {
        steps.sh """                   
            result=\$(xcrun altool --notarize-app --primary-bundle-id "${macBundleId}" --team-id "${macTeamId}" -u \$userName -p \$password --file ${inputFile})
            IN=\$(echo \${result} | grep -o "RequestUUID = \\S*")
            arrIN=(\${IN// = / })
            RequestUUID=\${arrIN[1]}
            
            while true
            do
                sleep 120
                result=\$(xcrun altool --notarization-info \${RequestUUID} -u \$userName -p \$password)
                IN=\$(echo \${result} | grep -o "Status: \\S*")
                arrIN=(\${IN//: / })
                Status=\${arrIN[1]}
                if [ "\$Status" = "success" ]; then
                    echo "Notarization successful."
                    break
                fi
                if [ "\$Status" = "invalid" ]; then
                    echo "Notarization failed."
                    exit 1
                fi
            done
            
            xcrun stapler staple ${inputFile}
        """
    }

    static String getLatestSuccessBuildVersion(Object steps, String jobName) {
        def builds = Jenkins.instance.getItemByFullName(jobName + '/' + Utils.getBranchName(steps)).builds

        String buildVersion
        def build
        for (int i = 0; i < builds.size(); i++) {
            build = builds.get(i)
            if (build.getResult().toString() == 'SUCCESS') {
                buildVersion = build.getDisplayName()
                steps.echo "buildVersion: ${buildVersion}"
                if (buildVersion.matches("[0-9.]+")) {
                    return buildVersion
                }
            }
        }
    }
}