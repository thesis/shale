#!/bin/bash

cleanup() {
    kill $(jobs -pr)
}

trap "cleanup" EXIT

PATH="$PATH:$PWD/phantomjs/bin/"

sleep 1

java -jar selenium -role node \
  -nodeConfig nodeConfig.json \
  -port 4444 &

java -jar selenium -role node \
  -nodeConfig nodeConfig.json \
  -port 4443 &

mkdir resources
cp test-config.clj resources/config.clj

lein with-profile aws uberjar
JAR_FILE=$(find target | grep "\.jar$" | grep -i shale | grep aws | head -1)
OTHER_JAR_FILES=$(find target | grep "\.jar$" | grep -i shale | head -1)
java -jar ${JAR_FILE-OTHER_JAR_FILES} &

COUNTER=0
until $(curl --output /dev/null --silent --head --fail http://localhost:5000) || [[ $COUNTER -gt 30 ]]; do
    printf '.'
    sleep 1
    let COUNTER+=1
done

lein test
STATUS=$?

exit $STATUS
