#/usr/bin/env bash

python wsgi.py &
sleep 2
SERVER_PID=$!
nosetests shale
STATUS=$?
kill $SERVER_PID
exit $STATUS
