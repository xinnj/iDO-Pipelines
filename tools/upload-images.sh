#! /bin/bash
set -eaxo pipefail

if [ "$1" != "" ]; then
  images=("$1")
else
  images=(\
    "quay.io/buildah/stable:v1.35.4" \
    "docker.io/xinnj/k8s-tools:1.0.1" \
    "docker.io/sonarsource/sonar-scanner-cli:10.0.3.1430_5.0.1" \
    "docker.io/xinnj/inbound-agent:4.13.3-1-alpine-jdk11-nolog" \
    "docker.io/xinnj/ssh-client:1.0.0" \
    "docker.io/alpine:3.20.1" \
    "docker.io/dwimberger/alpine-qr" \
    "docker.io/nginx:stable" \
    "docker.io/node:20.13.1-slim" \
    "docker.io/eclipse-temurin:21-jdk" \
    "docker.io/eclipse-temurin:21-jre"
  )
fi

set -u

podman login --tls-verify=false devops.bizconf.cn:30001 -u admin -p admin123

for img in "${images[@]}"; do
  export http_proxy="http://my-proxy:11080/"
  export https_proxy="http://my-proxy:11080/"
  podman pull $img || podman pull $img || podman pull $img
  push_image=$(echo $img | perl -pe 's/^.+?\/(.+)/devops.bizconf.cn:30001\/$1/')
  podman tag $img $push_image

  unset http_proxy
  unset https_proxy
  podman push --tls-verify=false $push_image || podman push --tls-verify=false $push_image || podman push --tls-verify=false $push_image
done