#!/usr/bin/env bash
# Builds every app image for linux/arm64 with the repository's multi-stage Dockerfile.
set -uo pipefail
repo=$(cd "$(dirname "${BASH_SOURCE[0]}")/../../../.." && pwd)
out=$repo/docs/evidence/local-2026-11-23-aws-iac/raw
cd "$repo" || exit 1
: > "$out/full-summary.tsv"
for app in erp-core event-relay edge-gateway stream-projection integration-adapter; do
  start=$(date +%s)
  if docker build --platform linux/arm64 --build-arg MODULE="apps/${app}" -f deploy/docker/Dockerfile \
       -t "materialflow-awscd/${app}:arm64-dockerfile" . > "$out/full-${app}.log" 2>&1; then status=pass; else status=fail; fi
  end=$(date +%s)
  arch=$(docker image inspect --format '{{.Os}}/{{.Architecture}}' "materialflow-awscd/${app}:arm64-dockerfile" 2>/dev/null || echo none)
  size=$(docker image inspect --format '{{.Size}}' "materialflow-awscd/${app}:arm64-dockerfile" 2>/dev/null || echo 0)
  printf '%s\t%s\t%s\t%ss\t%s\n' "$app" "$status" "$arch" "$((end-start))" "$size" >> "$out/full-summary.tsv"
done
echo "done" >> "$out/full-summary.tsv"
