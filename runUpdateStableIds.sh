#!/bin/bash
set -e

DIR=$(dirname "$(readlink -f "$0")") # Directory of the script -- allows the script to invoked from anywhere
cd $DIR

## Update repo
git pull
## Create new jar file with updateStableIds code
mvn clean compile assembly:single

## Ensures the correct jar file is obtained regardless of update-stable-ids project version
update_stable_ids_jar_file=$(ls target/update-stable-ids-*-jar-with-dependencies.jar)

## Run program
echo "java -jar $update_stable_ids_jar_file"
java -jar $update_stable_ids_jar_file

echo "Finished Updating Stable Identifiers"
