# Version: 1

import hashlib
import json
import os
import platform
import sys
import time
import requests


def copy_files(source_files, dest_folder):
    # dest_path must be a directory
    if not dest_folder.endswith('/'):
        dest_folder = dest_folder + '/'

    if os.path.isdir(source_files):
        source_files = source_files + '/*'

    if platform.system() == "Windows":
        orgcmd = "echo D | xcopy " + source_files + " " + dest_folder
        cmd = orgcmd.replace("/", "\\")
        cmd += " /S /Y /Q /H /R /F /I"
        os.system(cmd)
    else:
        cmd = "cp -af " + source_files + " " + dest_folder
        os.system(cmd)


def install(local_work_dir, file_name, folder_name, install_path):
    try:
        if not os.path.isdir(local_work_dir + folder_name):
            if platform.system() == "Windows":
                orgcmd = "powershell.exe -nologo -noprofile -command \"Expand-Archive -force -LiteralPath {} -DestinationPath {}\\\"".format(
                    local_work_dir + file_name, local_work_dir)
                cmd = orgcmd.replace("/", "\\")
                os.system(cmd)
            else:
                cmd = "unzip {} -d {}".format(local_work_dir + file_name, local_work_dir)
                os.system(cmd)
    except Exception as e:
        print(e)
        print("Unzip failed: {}{}".format(local_work_dir, file_name))
        sys.exit(1)

    for one_path in install_path:
        source_files = local_work_dir + folder_name + "/" + one_path["from"]
        dest_folder = one_path["to"]
        try:
            if not os.path.isdir(dest_folder):
                print("Create dir: ", dest_folder)
                os.makedirs(dest_folder)
            copy_files(source_files, dest_folder)
        except Exception as e:
            print(e)
            print("Copy from {} to {} failed!".format(source_files, dest_folder))
            sys.exit(1)
        print("Success install: {}, to: {}".format(source_files, dest_folder))


def http_download_sdk(local_work_dir, file_name, pkg_name, arch, servers, branch_dir, sha1):
    full_name = local_work_dir + file_name
    if os.path.exists(full_name):
        if sha1 == calculate_sha1(full_name):
            print("File: {} exists, skip download".format(full_name))
            return

    # Clean old versions
    if platform.system() == "Windows":
        orgcmd = "del {}/{}-*-{}.zip".format(local_work_dir, pkg_name, arch)
        cmd = orgcmd.replace("/", "\\")
        os.system(cmd)

        p = "{}/{}-*-{}".format(local_work_dir, pkg_name, arch)
        p = p.replace("/", "\\")
        cmd = "for /d %G in (\"{}\") do rd /s /q \"%~G\"".format(p)
        os.system(cmd)
    else:
        cmd = "rm -rf {}/{}-*-{}".format(local_work_dir, pkg_name, arch)
        os.system(cmd)

    success_download = False
    retry_num = 2
    for one_server in servers:
        success_download = False
        for x in range(retry_num):
            try:
                download_url = one_server + '/download/sdk-next/' + pkg_name + '/' + branch_dir + arch + '/' + file_name
                r = requests.get(download_url, timeout=2)
                r.raise_for_status()
            except Exception as e:
                print("Can't download {}, retry...".format(download_url))
            else:
                with open(full_name, "wb") as f:
                    f.write(r.content)
                if sha1 == calculate_sha1(full_name):
                    success_download = True
                    print("Success download {} from server: {}.".format(file_name, one_server))
                    break
                else:
                    print("SHA1 check failed! Retry...")
            time.sleep(5)
        if success_download:
            break

    if not success_download:
        print("Can't download {}!".format(file_name))
        sys.exit(1)


def http_download_info(file_name, pkg_name, arch, servers, branch_dir):
    for one_server in servers:
        try:
            download_url = one_server + '/download/sdk-next/' + pkg_name + '/' + branch_dir + arch + '/' + file_name
            r = requests.get(download_url, timeout=2)
            r.raise_for_status()
        except Exception as e:
            print("Can't download {}, retry...".format(download_url))
        else:
            with open(os.path.join("/sdk-info", file_name), "wb") as f:
                f.write(r.content)
            print("Success download {} from server: {}.".format(file_name, one_server))
            break
    else:
        print("Can't download {}!".format(file_name))
        sys.exit(1)


def find_latest_info(servers, arch, branch_dir, pkg_name, release_type):
    local_work_dir = "sdk-info/"
    if not os.path.isdir(local_work_dir):
        os.makedirs(local_work_dir)

    if release_type == "":
        info_file_name = pkg_name + "-latest.json"
    else:
        info_file_name = pkg_name + "-" + release_type + "-latest.json"

    http_download_info(info_file_name, pkg_name, arch, servers, branch_dir)

    with open(local_work_dir + info_file_name, 'r', encoding='utf-8') as f:
        info = json.load(f)

    return info


def calculate_sha1(filename, block_size=65536):
    hash_result = hashlib.sha1()
    with open(filename, "rb") as f:
        for block in iter(lambda: f.read(block_size), b""):
            hash_result.update(block)
    return hash_result.hexdigest()


if __name__ == '__main__':
    if len(sys.argv) != 4:
        print("USAGE: sync-sdk.py <working dir> <sdk info file> <branch name>")
        sys.exit(1)

    work_dir = sys.argv[1]
    sdk_info_file = sys.argv[2]
    branch = sys.argv[3]

    print("work_dir: ", work_dir)
    print("sdk_info_file: ", sdk_info_file)
    print("branch: ", branch)

    os.chdir(work_dir)

    local_work_dir = "sdk-tmp/"
    if not os.path.isdir(local_work_dir):
        os.makedirs(local_work_dir)
    # 读 json 配置文件
    with open(sdk_info_file, 'r', encoding='utf-8') as f:
        jinfo = json.load(f)

    servers = jinfo["servers"]

    for pkg in jinfo["packages"]:
        pkg_name = pkg["name"]

        arch = pkg["arch"]
        release_type = pkg["type", "Release"]
        download = pkg.get("download", True)

        use_branch = pkg.get("branch", True)
        if use_branch:
            branch_dir = ""
        else:
            branch_dir = branch + '/'

        download_latest = pkg.get("download_latest", False)

        if download_latest:
            info = find_latest_info(servers, arch, branch_dir, pkg_name, release_type)
            version = info["version"]
            sha1 = info["sha1"]
        else:
            version = pkg["version"]
            sha1 = pkg["sha1"]

        folder_name = pkg_name + "-" + version + "-" + arch + "-" + release_type

        if download:
            install_path = pkg["install_path"]

            file_name = folder_name + ".zip"

            http_download_sdk(local_work_dir, file_name, pkg_name, arch, servers, branch_dir, sha1)
            install(local_work_dir, file_name, folder_name, install_path)
