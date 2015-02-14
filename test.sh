#!/bin/bash

#
# Runs all tests.
#
# Prerequisites:
#   - Redis must be running.
#

export PATH="$PATH:$PWD/phantomjs/bin/"

lein version
lein compile
lein with-profile aws uberjar

mkdir resources
cp test-config.clj resources/config.clj

lein test
STATUS=$?

exit $STATUS
