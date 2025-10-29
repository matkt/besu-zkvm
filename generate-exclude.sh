#!/bin/bash

DEPENDENCIES_FILE="dependencies.txt"
CLASSES_FILE="class_load.log"
OUTPUT_FILE="exclusions.gradle"
s
global_exclusions=""

while IFS= read -r dependency; do
  groupId=$(echo "$dependency" | cut -d ':' -f 1 | sed 's/^[[:space:]-]*//')
  artifactId=$(echo "$dependency" | cut -d ':' -f 2)
  version=$(echo "$dependency" | cut -d ':' -f 3)

  if ! grep -q "$artifactId" "$CLASSES_FILE"; then
    global_exclusions+="    exclude group: '$groupId', module: '$artifactId'\n"
  fi
done < "$DEPENDENCIES_FILE"

echo "configurations.all {" > "$OUTPUT_FILE"
echo -e "$global_exclusions" >> "$OUTPUT_FILE"
echo "}" >> "$OUTPUT_FILE"

echo "File created $OUTPUT_FILE"