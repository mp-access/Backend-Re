#!/bin/bash

res="$(./get_size.bash)"
if [[ "$res" -gt 3000 ]]; then
  echo "ready"
else
  echo "NOT ready, db size is $res (less than 3000)"
fi

