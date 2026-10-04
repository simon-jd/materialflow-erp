# syntax=docker/dockerfile:1.7
FROM maven:3.9.16-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY . .
ARG MODULE=apps/erp-core
RUN --mount=type=cache,target=/root/.m2 mvn -B -ntp -pl "${MODULE}" -am -DskipTests package \
    && find "${MODULE}/target" -maxdepth 1 -type f -name '*.jar' ! -name '*.original' \
       -exec cp '{}' /opt/app.jar \;

FROM eclipse-temurin:25-jre-alpine
RUN apk upgrade --no-cache \
    && apk add --no-cache libstdc++ \
    && addgroup -S materialflow \
    && adduser -S -G materialflow -u 10001 materialflow
WORKDIR /opt/materialflow
COPY --from=build --chown=materialflow:materialflow /opt/app.jar app.jar
USER 10001:10001
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-Djava.security.egd=file:/dev/urandom", "-jar", "/opt/materialflow/app.jar"]
