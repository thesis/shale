#!/bin/bash

cleanup() {
    kill $(jobs -pr)

    # Workaround for bug in Flask in Python 2
    # https://github.com/mitsuhiko/flask/issues/988
    kill $(fuser -n tcp 5000 2> /dev/null)
}

trap "cleanup" EXIT

PATH="$PATH:$PWD/phantomjs/bin/"

sleep 1

java -jar selenium -role node \
  -nodeConfig nodeConfig.json \
  -port 5555 &

java -jar selenium -role node \
  -nodeConfig nodeConfig.json \
  -port 5554 &

JAR_FILE=`ls target | grep shale | grep standalone | head -1`

lein ring server

sleep 2
nosetests
STATUS=$?

exit $STATUS
