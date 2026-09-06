# Pinned multi-platform indexes; build for the kind node's architecture.
FROM eclipse-temurin:25-jdk-noble@sha256:534968c051301957beae735e7ba1db54d99ddecf08746d3b9d4f318cc132dbc3 AS builder
RUN sed -i 's|^URIs:.*|URIs: https://snapshot.ubuntu.com/ubuntu/20260905T000000Z/|' /etc/apt/sources.list.d/ubuntu.sources \
    && apt-get -o Acquire::Check-Valid-Until=false update \
    && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends python3 unzip curl \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /source
COPY . .
RUN --mount=type=cache,target=/root/.cache \
    ./kotlin package --module code-review --format executable-jar \
    && python3 agents/code-review/image/artifact.py build/tasks/_code-review_executableJarJvm/code-review-jvm-executable.jar \
    && mkdir /out \
    && cp build/tasks/_code-review_executableJarJvm/code-review-jvm-executable.jar /out/agent.jar

FROM eclipse-temurin:25-jre-noble@sha256:b4c93a50fc67612798db73d68ca3b0ee4ebdd51736e59cca370e689b9797037e AS java-runtime

FROM ubuntu:24.04@sha256:33ceb71981b602c1a7443a53469e4dba065f7503eab3078a2d7a57a2ab987517 AS runtime-files
COPY --from=java-runtime /etc/ssl/certs/ca-certificates.crt /etc/ssl/certs/ca-certificates.crt
RUN sed -i 's|^URIs:.*|URIs: https://snapshot.ubuntu.com/ubuntu/20260905T000000Z/|' /etc/apt/sources.list.d/ubuntu.sources \
    && apt-get -o Acquire::Check-Valid-Until=false update \
    && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends \
       git bash ripgrep ca-certificates python3 coreutils libp11-kit0 libtinfo6 zlib1g \
    && rm -rf /var/lib/apt/lists/*
COPY --from=java-runtime /opt/java/openjdk /opt/java/openjdk
COPY agents/code-review/image/runtime.py /runtime.py
RUN python3 /runtime.py

FROM scratch
COPY --from=runtime-files /rootfs/ /
COPY --from=builder --chown=10001:10001 /out/agent.jar /app/agent.jar
ENV PATH=/opt/java/openjdk/bin:/usr/bin:/bin \
    JAVA_TOOL_OPTIONS="-Djava.io.tmpdir=/tmp -Duser.home=/scratch" \
    GIT_TERMINAL_PROMPT=0
USER 10001:10001
WORKDIR /scratch
ENTRYPOINT ["/opt/java/openjdk/bin/java", "-jar", "/app/agent.jar"]