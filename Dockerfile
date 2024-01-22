FROM alpine
LABEL maintainer='xinnj@hotmail.com'

ARG VERSION

RUN apk add --no-cache git envsubst
RUN wget https://github.com/mikefarah/yq/releases/latest/download/yq_linux_amd64 -O /usr/bin/yq && chmod +x /usr/bin/yq

COPY --chown=1000:1000 additional-settings /additional-settings/
COPY --chown=1000:1000 --chmod=0755 customization/customization.sh /

COPY resources /jenkins-lib/resources/
COPY src /jenkins-lib/src/
COPY vars /jenkins-lib/vars/
COPY LICENSE /jenkins-lib/
RUN chown -R 1000:1000 /jenkins-lib
USER 1000:1000
RUN cd /jenkins-lib \
    && git init \
    && git branch -m main \
    && git add . \
    && git config --local user.email "jenkins@idocluster" \
    && git config --local user.name "jenkins" \
    && git commit -m "${VERSION}" \
    && git config --local --add safe.directory /home/jenkins/lib

CMD /customization.sh