#!/bin/bash

cleanup() {
    kill $(jobs -pr)
}

trap "cleanup" EXIT

cd ../..

PATH="$PATH:$PWD/phantomjs/bin/"

sleep 1

java -jar selenium -role node \
  -nodeConfig nodeConfig.json \
  -port 5555 &

java -jar selenium -role node \
  -nodeConfig nodeConfig.json \
  -port 5554 &

lein uberjar
JAR_FILE=$(ls target | grep -i shale | grep standalone | head -1)
java -jar ./target/$JAR_FILE &

COUNTER=0
until $(curl --output /dev/null --silent --head --fail http://localhost:5000) || [[ $COUNTER -gt 30 ]]; do
    printf '.'
    sleep 1
    let COUNTER+=1
done

lein test
STATUS=$?

exit $STATUS
