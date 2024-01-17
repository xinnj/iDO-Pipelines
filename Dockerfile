FROM alpine
LABEL maintainer='xinnj@hotmail.com'

ARG VERSION

RUN apk add --no-cache git
RUN wget https://github.com/mikefarah/yq/releases/latest/download/yq_linux_amd64 -O /usr/bin/yq && chmod +x /usr/bin/yq

COPY resources /jenkins-lib/resources/
COPY src /jenkins-lib/src/
COPY vars /jenkins-lib/vars/
COPY LICENSE /jenkins-lib/
RUN cd /jenkins-lib \
    && git init \
    && git branch -m main \
    && git add . \
    && git config --local user.email "jenkins@idocluster" \
    && git config --local user.name "jenkins" \
    && git commit -m "${VERSION}" \
    && chown -R 1000:1000 /jenkins-lib