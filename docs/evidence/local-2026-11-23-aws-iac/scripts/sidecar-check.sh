#!/usr/bin/env bash
# Local checks of the stream-projection topic init script (against a PLAINTEXT single broker) and
# of the trace collector configuration the task definitions pass as AOT_CONFIG_CONTENT.
set -euo pipefail
repo=$(cd "$(dirname "${BASH_SOURCE[0]}")/../../../.." && pwd)
raw=$repo/docs/evidence/local-2026-11-23-aws-iac/raw
net=mf-awscd-kafka
cleanup() { docker rm -f mf-awscd-kafka mf-awscd-otel >/dev/null 2>&1 || true; docker network rm $net >/dev/null 2>&1 || true; }
trap cleanup EXIT
cleanup
docker network create $net >/dev/null
docker run -d --name mf-awscd-kafka --network $net \
  -e KAFKA_NODE_ID=1 -e KAFKA_PROCESS_ROLES=broker,controller \
  -e KAFKA_LISTENERS=PLAINTEXT://:9092,CONTROLLER://:9093 -e KAFKA_ADVERTISED_LISTENERS=PLAINTEXT://mf-awscd-kafka:9092 \
  -e KAFKA_LISTENER_SECURITY_PROTOCOL_MAP=CONTROLLER:PLAINTEXT,PLAINTEXT:PLAINTEXT -e KAFKA_CONTROLLER_LISTENER_NAMES=CONTROLLER \
  -e KAFKA_CONTROLLER_QUORUM_VOTERS=1@mf-awscd-kafka:9093 -e KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR=1 \
  apache/kafka:4.1.2 >/dev/null
until docker exec mf-awscd-kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --list >/dev/null 2>&1; do sleep 2; done
run_init() {
  docker run --rm --network $net -e KAFKA_BOOTSTRAP_SERVERS=mf-awscd-kafka:9092 -e KAFKA_SECURITY_PROTOCOL=PLAINTEXT \
    -e REPLICATION_FACTOR=1 apache/kafka:4.1.2 /bin/sh -ec "$(cat $repo/deploy/terraform/aws/scripts/create-topics.sh)"
}
echo "create-topics#1 exit=$(run_init > $raw/create-topics-1.log 2>&1; echo $?)"; cat $raw/create-topics-1.log
echo "create-topics#2 exit=$(run_init > $raw/create-topics-2.log 2>&1; echo $?)"; cat $raw/create-topics-2.log
echo "topics: $(docker exec mf-awscd-kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --list | sort | tr '\n' ' ')"

config=$(cat <<'YAML'
"exporters":
  "awsxray":
    "region": "us-east-1"
"processors":
  "batch": {}
"receivers":
  "otlp":
    "protocols":
      "http":
        "endpoint": "127.0.0.1:4318"
"service":
  "pipelines":
    "traces":
      "exporters":
      - "awsxray"
      "processors":
      - "batch"
      "receivers":
      - "otlp"
YAML
)
docker run -d --name mf-awscd-otel --platform linux/arm64 -e AOT_CONFIG_CONTENT="$config" amazon/aws-otel-collector:v0.51.0 >/dev/null
sleep 8
docker logs mf-awscd-otel > $raw/otel-collector.log 2>&1
echo "collector running=$(docker inspect -f '{{.State.Running}}' mf-awscd-otel)"
grep -Eo 'Everything is ready[^"]*|Starting HTTP server[^,]*|endpoint": "[^"]*' $raw/otel-collector.log | head -3
