#!/bin/sh
set -eu

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" \
  -v app_password="$POSTGRES_APP_PASSWORD" <<'EOSQL'
create role secureflow_app login password :'app_password';
grant connect on database secureflow to secureflow_app;
grant usage on schema public to secureflow_app;
alter default privileges in schema public grant select, insert, update, delete on tables to secureflow_app;
alter default privileges in schema public grant usage, select on sequences to secureflow_app;
EOSQL
