#! /bin/sh
set -euaxo pipefail

mkdir -p /home/jenkins/agent/jenkins_backup
cp -rn /additional-settings/* /var/jenkins_home/

mkdir -p /home/jenkins/lib/active
rm -rf /home/jenkins/lib/active/*
cp -rT /jenkins-lib /home/jenkins/lib/active

cd /home/jenkins/lib/active

if [ -e /var/jenkins_home/customization/config/system.yaml ]; then
  cp -f /var/jenkins_home/customization/config/system.yaml /var/jenkins_home/customization/config/system.yaml.bak
  yq '. *= load("/var/jenkins_home/customization/config/system.yaml.bak")' /jenkins-lib/resources/config/system.yaml >/var/jenkins_home/customization/config/system.yaml
  envsubst < "/var/jenkins_home/customization/config/system.yaml" > "./resources/config/system.yaml"
else
  envsubst < "./resources/config/system.yaml" > "./resources/config/system.yaml.tmp"
  mv -f ./resources/config/system.yaml.tmp ./resources/config/system.yaml
fi

if [ -e /var/jenkins_home/customization/config/team.yaml ]; then
  cp -f /var/jenkins_home/customization/config/team.yaml /var/jenkins_home/customization/config/team.yaml.bak
  yq '. *= load("/var/jenkins_home/customization/config/team.yaml.bak")' /jenkins-lib/resources/config/team.yaml >/var/jenkins_home/customization/config/team.yaml
  envsubst < "/var/jenkins_home/customization/config/team.yaml" > "./resources/config/team.yaml"
else
  envsubst < "./resources/config/team.yaml" > "./resources/config/team.yaml.tmp"
  mv -f ./resources/config/team.yaml.tmp ./resources/config/team.yaml
fi

if [ $(git status --porcelain | wc -l) -gt 0 ]; then
  git add .
  git commit -m "Customize Config"
fi
