FROM alpine
LABEL maintainer='xinnj@hotmail.com'

ARG VERSION

RUN apk add --no-cache git

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