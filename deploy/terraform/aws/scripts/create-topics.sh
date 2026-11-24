#!/bin/sh
# Init container of stream-projection (kafka-tools image, a mirror of apache/kafka).
# Same topics as deploy/compose/compose.yml's kafka-init; --if-not-exists makes reruns no-ops.
# KAFKA_SECURITY_PROTOCOL defaults to SSL for MSK; PLAINTEXT is only for local testing.
set -eu
printf 'security.protocol=%s\n' "${KAFKA_SECURITY_PROTOCOL:-SSL}" > /tmp/client.properties
for topic in materialflow.domain-events.v1 materialflow.domain-events.v1.DLQ materialflow.projection-dlq.v1; do
  /opt/kafka/bin/kafka-topics.sh --bootstrap-server "$KAFKA_BOOTSTRAP_SERVERS" \
    --command-config /tmp/client.properties \
    --create --if-not-exists --topic "$topic" --partitions 1 --replication-factor "$REPLICATION_FACTOR"
done
