#! /bin/sh
set -euaxo pipefail

mkdir -p /home/jenkins/lib
rm -rf /home/jenkins/lib/*
cp -rT /jenkins-lib /home/jenkins/lib
