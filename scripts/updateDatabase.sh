#!/bin/bash

## This script will create or replace a database using a mysql dump file. It takes the DB name and dump filepath as arguements.
## A config file populated with mySQL username and password is also required.
## author: jcook

dbFilepath=
dbName=
configFilepath=config.properties
## Parse execution arguments
while (( "$#" )); do
	case "$1" in
		-f|--file)
			dbFilepath=$2;
			shift 2;
			;;
		-d|--database)
			dbName=$2;
			shift 2;
			;;
		-c|--config)
			configFilepath=$2;
			shift 2;
			;;
		-*|--*=)
			echo "Error: Unsupported flag $1"
			exit 1
	esac
done

## If missing arguments, explain usage
if [ -z "$dbFilepath" ] || [ -z "$dbName" ]
then
	echo "Create or replace mySQL databases";
	echo "Usage: bash updateDatabase.sh -f databaseFile -d databaseName [-c configFile] ";
	exit 1
fi

## Parse config file for username and password properties
echo "Reading $configFilepath";
username=
password=
host=localhost
port=3306
while read line; do
	if [[ $line == username* ]]
 	then
 		username=${line#*=}
 	elif [[ $line == password* ]]
 	then
 		password=${line#*=}
	elif [[ $line == host* ]]
	then
		host=${line#*=}
	elif [[ $line == port* ]]
	then
		port=${line#*=}
 	fi
done < $configFilepath

## Improperly formatted or missing config file
if [ -z "$username" ] || [ -z "$password" ]
then
	echo "Could not find username and/or password in $configFilepath";
	exit 1
fi

echo "Updating $dbName with $dbFilepath";
echo "Backing up $dbName";

## Take archive of DB, drop it and create a new, empty one
mysqldump -u$username -p$password -h$host -P$port $dbName > $dbName.backup.dump
echo "Finished backing up $dbName";
echo "Updating $dbName with $dbFilepath";
mysql -u$username -p$password -h$host -P$port -e 'drop database if exists $dbName; create database $dbName'

## If dump is gzipped, must use 'zcat'
catCommand=cat
if [ $dbFilepath == *.gz ]
then
	catCommand=zcat
fi

## Restore database using 'cat' piped to mysql
cmd="$catCommand $dbFilepath | mysql -u$username -p$password -h$host -P$port $dbName"
echo "Restoring updated $dbName";
eval $cmd;
echo "Finished database update";
