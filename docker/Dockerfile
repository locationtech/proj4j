# A clean, pinned Linux build environment for proj4j's five check suites.
#
# WHY THIS EXISTS. Running the suites on the authoring Mac is unconvincing: the JDK, the OS, the
# default Locale and a ~/.m2 full of other agents' snapshots all differ from any real runner. This
# image fixes all four. It is not a replacement for .github/workflows/** - docker/run.sh reuses
# those workflows' exact Maven commands so the two cannot drift.
#
# PINNING.
#   * Base image is pinned by DIGEST, not just by tag. The digest below is the multi-arch manifest
#     LIST digest, so it resolves to linux/arm64 on this Apple Silicon host and to linux/amd64 on a
#     normal CI runner - the same pin, two architectures. `docker buildx imagetools inspect` on it
#     shows both.
#   * Maven is fetched from archive.apache.org (immutable, unlike dlcdn) and its tarball is checked
#     against the SHA-512 Apache publishes. 3.9.16 is the version on the authoring machine.
#   * apt package versions are NOT pinned. They are ca-certificates/curl/git/rsync/locales only -
#     nothing on the measurement path - and pinning them would make the image un-buildable the
#     first time Ubuntu rolls a point release. This is the one genuinely floating input; it is
#     called out in docker/README.md rather than hidden.
#
# DELIBERATELY NOT INSTALLED: `bc`. Three of the workflows' guard steps say in a comment that they
# use awk "because bc is not guaranteed present on every runner image". Leaving bc out keeps that
# claim under test instead of taking it on trust - if a guard ever grows a `bc` call it will die
# here rather than on a runner.

FROM eclipse-temurin:21.0.11_10-jdk-noble@sha256:35685c7e23352983a48882d97cd9875f5284c228db71d1e2476e5e6c1bab1080

# 3.9.16, and the SHA-512 as published at
# https://archive.apache.org/dist/maven/maven-3/3.9.16/binaries/apache-maven-3.9.16-bin.tar.gz.sha512
ARG MAVEN_VERSION=3.9.16
ARG MAVEN_SHA512=831a8591fe20c8243b1dbe7d71e3244f31d1665b0804b2e825e38cbbe5ce0cafb8338851f90780735568773e0a6cd07bbec107cda0b896b008b861075358b6f6

RUN set -eux; \
    apt-get update; \
    DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends \
        ca-certificates \
        curl \
        git \
        rsync \
        locales \
        procps \
        tzdata; \
    rm -rf /var/lib/apt/lists/*; \
    # The reference environment of ci.yaml's blocking determinism leg is en_US / UTF-8 / UTC.
    # The base image generates only C.UTF-8, so en_US.UTF-8 would silently fall back to C -
    # ci.yaml's own `locale-gen` step exists for exactly this and is mirrored here.
    sed -i 's/^# *en_US.UTF-8/en_US.UTF-8/' /etc/locale.gen; \
    locale-gen en_US.UTF-8

# OPTIONAL CORPORATE CA, AND IT IS A HOST QUIRK RATHER THAN PART OF THE DESIGN.
#
# The machine this was written on sits behind a TLS-intercepting firewall: archive.apache.org and
# repo.maven.apache.org both present a certificate issued by an internal CA, so an un-doctored
# container gets `curl: (60) SSL certificate problem: self-signed certificate in certificate chain`
# and Maven cannot resolve a single dependency. docker/run.sh detects the host's bundle (from
# $SSL_CERT_FILE / $REQUESTS_CA_BUNDLE / /etc/ssl/certs/*-ca-bundle.crt) and drops it into the build
# context; if there is none - which is the case on a normal machine and on GitHub Actions -
# extra-ca.crt is written EMPTY and this stage does nothing at all.
#
# Two truststores have to be taught, not one: `update-ca-certificates` covers curl/git, and a
# separate keytool import covers the JVM, whose cacerts file the OS store does not feed. Missing the
# second half looks like a Maven "peer not authenticated" failure with a working `curl`.
COPY extra-ca.crt /tmp/extra-ca.crt
RUN set -eux; \
    if [ -s /tmp/extra-ca.crt ]; then \
        cp /tmp/extra-ca.crt /usr/local/share/ca-certificates/extra-ca.crt; \
        update-ca-certificates; \
        csplit -z -s -f /tmp/cacert- -b '%02d.pem' /tmp/extra-ca.crt '/-----BEGIN CERTIFICATE-----/' '{*}'; \
        for f in /tmp/cacert-*.pem; do \
            keytool -importcert -noprompt -trustcacerts -cacerts -storepass changeit \
                    -alias "extra-$(basename "$f" .pem)" -file "$f" >/dev/null || true; \
        done; \
        rm -f /tmp/cacert-*.pem; \
        echo "injected $(grep -c 'BEGIN CERTIFICATE' /tmp/extra-ca.crt) extra CA certificate(s)"; \
    else \
        echo "no extra CA bundle supplied - using the stock trust stores"; \
    fi; \
    rm -f /tmp/extra-ca.crt

RUN set -eux; \
    curl -fsSL -o /tmp/maven.tar.gz \
        "https://archive.apache.org/dist/maven/maven-3/${MAVEN_VERSION}/binaries/apache-maven-${MAVEN_VERSION}-bin.tar.gz"; \
    printf '%s  /tmp/maven.tar.gz\n' "${MAVEN_SHA512}" | sha512sum -c -; \
    mkdir -p /opt/maven; \
    tar -xzf /tmp/maven.tar.gz -C /opt/maven --strip-components=1; \
    rm -f /tmp/maven.tar.gz; \
    ln -s /opt/maven/bin/mvn /usr/local/bin/mvn; \
    mvn -v

# The working tree is copied out of a read-only bind mount into /work, so it is owned by root and
# git's dubious-ownership check is not in play - but the copy also brings .git, which jgitver reads
# to compute the version. Set it anyway: the failure mode is a confusing "detected dubious
# ownership" during the reactor build, which looks nothing like its cause.
RUN git config --global --add safe.directory '*'

# The reference environment. ci.yaml's blocking determinism leg is en_US / UTF-8 / UTC / x64; every
# other leg in that matrix is advisory. Reproducing the blocking one is the useful default.
ENV LANG=en_US.UTF-8 \
    LC_ALL=en_US.UTF-8 \
    TZ=UTC \
    MAVEN_OPTS="-Xmx2g"

# docker/run.sh reads this to decide whether it is the host driver or the in-container runner. It
# lives in the image rather than being passed with -e so that `docker run <image> bash` also lands
# in the right mode.
ENV PROJ4J_IN_CONTAINER=1

WORKDIR /work
ENTRYPOINT ["/bin/bash"]
CMD ["/src/docker/run.sh", "all"]
