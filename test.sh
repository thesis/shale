#!/bin/bash

cleanup() {
    kill $(jobs -pr)
}

trap "cleanup" EXIT

PATH="$PATH:$PWD/phantomjs/bin/"

sleep 1

lein version
lein typed check
lein compile
lein with-profile aws uberjar

java -jar selenium -role node \
  -nodeConfig nodeConfig.json \
  -port 4444 &

java -jar selenium -role node \
  -nodeConfig nodeConfig.json \
  -port 4443 &

mkdir resources
cp test-config.clj resources/config.clj

JAR_FILE=$(find target | grep "\.jar$" | grep -i shale | grep aws | head -1)
OTHER_JAR_FILES=$(find target | grep "\.jar$" | grep -i shale | tail -1)
export CONFIG_FILE=$( readlink -f "./resources/config.clj" )
java -jar ${JAR_FILE:-$OTHER_JAR_FILES} &

COUNTER=0
while [[ $COUNTER -lt 30 ]]; do
    curl --output /dev/null --silent --head --fail http://localhost:5000 && curl --output /dev/null --silent --head --fail http://localhost:4444 && curl --output /dev/null --silent --head --fail http://localhost:4443 && break
    printf '.'
    sleep 1
    let COUNTER+=1
done

curl -XPOST http://localhost:5000/nodes/refresh

lein test
STATUS=$?

exit $STATUS
