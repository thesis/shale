#!/bin/bash

export PATH="$PATH:$PWD/phantomjs/bin/"

lein version
lein compile
lein with-profile aws uberjar

mkdir resources
cp test-config.clj resources/config.clj

lein test
