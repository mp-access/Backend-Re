#!/bin/bash

res="$(docker exec -i postgres psql -U admin -d access 2>/dev/null <<< "select pg_database_size('access')/1024/1024" | sort -n | tail -n 1)"
[[ -z "$res" ]] && echo "0" || echo $res

