#!/bin/bash

cleanup() {
    kill $(jobs -pr)

    # Workaround for bug in Flask in Python 2
    # https://github.com/mitsuhiko/flask/issues/988
    kill $(fuser -n tcp 5000 2> /dev/null)
}

trap "cleanup" EXIT

PATH="$PATH:$PWD/phantomjs/bin/"

/bin/java -jar selenium -role hub &

sleep 1

/bin/java -jar selenium -role node \
  -hub http://localhost:4444/register/grid \
  -nodeConfig ./libs/selenium/nodeConfig.json &

python wsgi.py &

sleep 2
nosetests shale
STATUS=$?

exit $STATUS
