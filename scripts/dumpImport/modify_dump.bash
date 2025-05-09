#!/bin/bash

# This script will modify an existing postgres dump from a production
# environment to make it compatible with local ACCESS unit testing. It makes
# the following modifications:
#  - do not drop/recreate the postgres admin user (credentials stay the same)
#  - Modify keycloak admin account to use the default ("admin") password
#  - Add test users
#  - Add test user attributes
#
# Note that the test user and attribute identifiers in the excerpts must match
# the identifiers used in access/realm.json in the Infrastructre repo

dump="$1"

if [[ -z "$1" ]]; then
  echo "Expected dump path as first parameter, exiting."
  exit 1
fi

if [[ -e "modified.sql" ]]; then
  echo "modified.sql exists, skipping modification script."
  exit 0
fi

echo -n "Searching for keycloak admin user id... "
admin_id="$(grep -P '^.*admin\t[0-9]+\t\\N\t0' "$dump" | awk -F'\t' '{print $1}')"
echo "found $admin_id"

echo -n "Searching for keycloak admin credential... "
result="$(grep -nP '\tpassword\t'"${admin_id}"'\t.*value.*salt' "$dump")"
cred_line="$(echo "$result" | awk -F':' '{print $1}')"
cred="$(echo "$result" | sed -e 's/^[0-9]*://g')"
echo "found on line $cred_line"

echo -n "Generating credential for password 'admin'... "
generated="$(./generate_keycloak_credential.py | sed -e 's/+/\\+/g;s/\//\\\//g')"
new_cred="$(echo "$cred" | sed -e 's/"value":"[^"]*/"value":"'"$generated"'/;s/"salt":"[^"]*/"salt":"1234ASDFasdf/')"
echo "done"

echo -n "Writing copy with updated credential and test users/attributes to modified.sql... "
before=$((cred_line-1))
attribute_line="$(grep -n "COPY public.user_attribute" "$dump" | awk -F':' '{print $1}')"
users_line="$(grep -n "COPY public.user_entity" "$dump" | awk -F':' '{print $1}')"
head -n "$before" "$dump" | grep -Ev 'DROP ROLE admin|CREATE ROLE admin|ALTER ROLE admin' > modified.sql
echo "$new_cred" >> modified.sql
after=$((cred_line+1))
sed -n "${after},${attribute_line}p" "$dump" >> modified.sql
cat test_user_attributes.sql.excerpt >> modified.sql
after=$((attribute_line+1))
sed -n "${after},${users_line}p" "$dump" >> modified.sql
cat test_users.sql.excerpt >> modified.sql
after=$((users_line+1))
tail -n "+${after}" "$dump" >> modified.sql
echo "All done"

