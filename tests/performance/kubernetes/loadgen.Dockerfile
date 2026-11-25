# Test-client image for the AKS runs (tests/performance/README.md, "Running on AKS"): Apache JMeter
# 5.6.3 from the official Apache archive and k6 1.8.2 from its GitHub release, each checked
# against a pinned checksum, plus the reservation contention harness JAR. Test plans and scripts are
# streamed in by the runners, so the image changes only with the tools and the JAR. Build it for linux/arm64
# (the loadgen pool is Bpsv2) with the performance module as context, after `mvn ... package`:
#   docker buildx build --platform linux/arm64 -f tests/performance/kubernetes/loadgen.Dockerfile \
#     -t <acr>/materialflow/loadgen:<tag> --push tests/performance
FROM eclipse-temurin:21-jre-noble

ARG JMETER_VERSION=5.6.3
# https://archive.apache.org/dist/jmeter/binaries/apache-jmeter-5.6.3.tgz.sha512 (same value on downloads.apache.org)
ARG JMETER_SHA512=5978a1a35edb5a7d428e270564ff49d2b1b257a65e17a759d259a9283fc17093e522fe46f474a043864aea6910683486340706d745fcdf3db1505fd71e689083
ARG K6_VERSION=1.8.2
# k6-v1.8.2-checksums.txt from the GitHub release
ARG K6_SHA256_LINUX_ARM64=8b4e55024ffc8ed7ed4caabca054dfb5bafe16e7ee4235de57d01bf58d69e0d7

RUN apt-get update \
    && apt-get install -y --no-install-recommends ca-certificates curl jq \
    && rm -rf /var/lib/apt/lists/*
RUN curl -fsSLo /tmp/jmeter.tgz "https://archive.apache.org/dist/jmeter/binaries/apache-jmeter-${JMETER_VERSION}.tgz" \
    && echo "${JMETER_SHA512}  /tmp/jmeter.tgz" | sha512sum -c - \
    && tar -xzf /tmp/jmeter.tgz -C /opt \
    && ln -s "/opt/apache-jmeter-${JMETER_VERSION}" /opt/jmeter \
    && rm /tmp/jmeter.tgz
RUN curl -fsSLo /tmp/k6.tgz "https://github.com/grafana/k6/releases/download/v${K6_VERSION}/k6-v${K6_VERSION}-linux-arm64.tar.gz" \
    && echo "${K6_SHA256_LINUX_ARM64}  /tmp/k6.tgz" | sha256sum -c - \
    && tar -xzf /tmp/k6.tgz -C /tmp \
    && install -m 0755 "/tmp/k6-v${K6_VERSION}-linux-arm64/k6" /usr/local/bin/k6 \
    && rm -rf /tmp/k6.tgz "/tmp/k6-v${K6_VERSION}-linux-arm64"

COPY target/materialflow-benchmarks.jar /opt/materialflow/materialflow-benchmarks.jar

RUN useradd --system --uid 10001 --home-dir /work --no-create-home loadgen
USER 10001:10001
ENV JMETER_HOME=/opt/jmeter PATH=/opt/jmeter/bin:${PATH}
WORKDIR /work
CMD ["sleep", "infinity"]
