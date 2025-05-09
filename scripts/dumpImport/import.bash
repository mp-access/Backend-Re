#!/bin/bash

if [[ ! -e "modified.sql" ]]; then
  echo "modified.sql does not exist, run modify_dump.bash to generate it first"
  exit 1
fi

res="$(./get_size.bash)"
if [[ -z "$res" || "$res" -lt 3000 || "$1" = "--force" ]]; then
  echo "Importing modified dump..."
  docker stop keycloak
  docker exec -it postgres psql -U admin -d postgres -c "select pg_terminate_backend(pid) from pg_stat_activity where datname = 'access';"
  docker exec -it postgres psql -U admin -d postgres -c "drop database if exists access with (force);"
  docker exec -it postgres psql -U admin -d postgres -c "create database access;"
  docker exec -i postgres psql -U admin -d access < "modified.sql"
  timeout="10"
  echo "Restarting keycloak container and waiting $timeout seconds"
  docker start keycloak
  sleep "$timeout"
else
  echo "Large access database detected (${res}MB), not importing"
  echo "Re-run with --force to drop existing access db and import anyway"
fi

