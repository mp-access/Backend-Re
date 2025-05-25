To run performance tests, a large ACCESS db dump is needed locally.

  1. Obtain a dump from the production environment, e.g.:

   ```
   docker exec -it postgres pg_dumpall -c -U admin > dump_`date +%Y-%m-%d"_"%H_%M_%S`.sql
   ```

  2. Change your CWD to `scripts/dumpImport`
  3. run `./modify_dump.bash <path_to_dump.sql>`. See the script for more information on what this does.
  4. run `./import.bash` **Note:** this will drop all existing data!

