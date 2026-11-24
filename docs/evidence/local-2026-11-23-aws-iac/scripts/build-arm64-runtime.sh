#!/usr/bin/env bash
# Packages host-built JARs into linux/arm64 images with Dockerfile.runtime, as cd-aws does.
set -uo pipefail
out=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)/raw
repo=$(cd "$(dirname "${BASH_SOURCE[0]}")/../../../.." && pwd)
: "${JAVA_HOME:?Set JAVA_HOME to a Java 21 JDK}"
cd "$repo" || exit 1
start=$(date +%s)
mvn -B -ntp -DskipTests package > "$out/mvn-package.log" 2>&1; echo "mvn package exit=$? in $(( $(date +%s) - start ))s" > "$out/runtime-summary.txt"
ctx=$(mktemp -d)
cp deploy/docker/Dockerfile.runtime "$ctx/Dockerfile"
for app in erp-core event-relay edge-gateway stream-projection integration-adapter; do
  cp "apps/${app}/target/${app}-0.1.0-SNAPSHOT.jar" "$ctx/${app}.jar"
  s=$(date +%s)
  docker buildx build --platform linux/arm64 --provenance=false --build-arg "JAR_FILE=${app}.jar" \
    -t "materialflow-awscd/${app}:arm64-runtime" --load "$ctx" > "$out/runtime-${app}.log" 2>&1
  rc=$?
  jar_sha=$(shasum -a 256 "apps/${app}/target/${app}-0.1.0-SNAPSHOT.jar" | cut -c1-12)
  img_sha=$(docker run --rm --platform linux/arm64 --entrypoint sha256sum "materialflow-awscd/${app}:arm64-runtime" /opt/materialflow/app.jar 2>/dev/null | cut -c1-12)
  printf '%s exit=%s %ss arch=%s jar=%s image_jar=%s\n' "$app" "$rc" "$(( $(date +%s) - s ))" \
    "$(docker image inspect --format '{{.Architecture}}' materialflow-awscd/${app}:arm64-runtime 2>/dev/null)" "$jar_sha" "$img_sha" >> "$out/runtime-summary.txt"
done
rm -rf "$ctx"
echo "done" >> "$out/runtime-summary.txt"
