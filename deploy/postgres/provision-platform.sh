#!/bin/sh
set -eu
: "${PLATFORM_DB_PASSWORD:?Set PLATFORM_DB_PASSWORD}"
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" \
  --set=platform_password="$PLATFORM_DB_PASSWORD" <<'SQL'
SELECT format('CREATE ROLE fan_platform LOGIN PASSWORD %L', :'platform_password')
WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'fan_platform') \gexec
SELECT format('CREATE DATABASE fan_ai_agent_v2 OWNER fan_platform')
WHERE NOT EXISTS (SELECT 1 FROM pg_database WHERE datname = 'fan_ai_agent_v2') \gexec
SQL
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname fan_ai_agent_v2 \
  -c 'CREATE EXTENSION IF NOT EXISTS vector;'
