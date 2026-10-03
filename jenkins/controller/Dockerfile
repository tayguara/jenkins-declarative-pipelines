FROM jenkins/jenkins:2.580.1-lts-jdk21

# Plugins are baked into the image; the setup wizard is disabled because the
# whole instance is configured as code (see jenkins/casc/jenkins.yaml).
COPY --chown=jenkins:jenkins plugins.txt /usr/share/jenkins/ref/plugins.txt
RUN jenkins-plugin-cli --plugin-file /usr/share/jenkins/ref/plugins.txt

# No secret is set here: the admin password and deploy tokens arrive as files
# under /run/secrets at runtime and are read by JCasC.
ENV JAVA_OPTS="-Djenkins.install.runSetupWizard=false" \
    CASC_JENKINS_CONFIG=/var/jenkins_conf/jenkins.yaml
