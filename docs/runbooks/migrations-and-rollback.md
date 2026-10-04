# Migration and release rollback

Flyway validation failure must keep readiness false. Never edit V1–V4 after they
have been released; append a forward migration and test both an empty database and
the previous schema. Application rollback is allowed only when the previous image
is compatible with every applied migration. Helm rollback must preserve Secrets,
PVCs and Kafka consumer identity. Capture image digest, chart version, migration
version and evidence index before promotion.
