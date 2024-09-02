# Version: 2
# Capture return code

# Version: 1

import sys
import os
import requests
import platform


def http_download_script():
    servers = ["https://devops.bizconf.cn", "https://packages.bizconf.cn"]

    for one_server in servers:
        try:
            r = requests.get(one_server + '/download/tools/sync-sdk-downloaded.py', timeout=2)
            r.raise_for_status()
        except Exception as e:
            print("Can't download sync-sdk-downloaded.py from server: {}, try next server.".format(one_server))
        else:
            with open(os.path.join(os.path.dirname(os.path.abspath("__file__")), 'sync-sdk-downloaded.py'), "wb") as f:
                f.write(r.content)
            print("Success download sync-sdk-downloaded.py from server: {}.".format(one_server))
            break
    else:
        print("Can't download sync-sdk-downloaded.py!")
        sys.exit(1)


if __name__ == '__main__':
    if len(sys.argv) != 4:
        print("USAGE: sync-sdk.py <working dir> <json file> <branch name>")
        sys.exit(1)

    work_dir = sys.argv[1]
    json_file = sys.argv[2]
    branch = sys.argv[3]

    os.chdir(work_dir)

    http_download_script()

    if platform.system() == "Windows":
        orgcmd = "python ./sync-sdk-full.py {} {} {}".format(work_dir, json_file, branch)
        cmd = orgcmd.replace("/", "\\")
        r = os.system(cmd)
    else:
        cmd = "python3 ./sync-sdk-full.py {} {} {}".format(work_dir, json_file, branch)
        r = os.system(cmd)

    if r != 0:
        print("Sync SDK failed!")
        sys.exit(1)
