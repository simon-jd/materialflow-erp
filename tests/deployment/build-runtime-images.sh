#!/usr/bin/env bash
set -euo pipefail
root=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
tag=${1:?Usage: build-runtime-images.sh TAG [directory containing prebuilt JARs]}
jar_dir=${2:-}
context=$(mktemp -d)
trap 'rm -rf "${context}"' EXIT
cp "${root}/deploy/docker/Dockerfile.runtime" "${context}/Dockerfile"
for app in erp-core event-relay edge-gateway stream-projection integration-adapter; do
  source=${jar_dir:-${root}/apps/${app}/target}/${app}-0.1.0-SNAPSHOT.jar
  test -s "${source}"
  cp "${source}" "${context}/${app}.jar"
  sha256sum "${source}"
  docker build --build-arg "JAR_FILE=${app}.jar" -t "materialflow-qualification/${app}:${tag}" "${context}"
done
