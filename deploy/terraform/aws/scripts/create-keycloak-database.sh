#!/bin/sh
# Init container of keycloak (postgres client image); PG* variables come from the task definition.
# RDS creates only the application database, and CREATE DATABASE has no IF NOT EXISTS.
set -eu
if psql -tAc "SELECT 1 FROM pg_database WHERE datname = 'keycloak'" | grep -q 1; then
  echo "database keycloak already exists"
else
  psql -v ON_ERROR_STOP=1 -c "CREATE DATABASE keycloak"
fi
