#!/bin/bash

PHANTOM_VERSION='1.9.7'
PHANTOM_DIR_NAME="phantomjs-$PHANTOM_VERSION-linux-x86_64"
PHANTOM_FILE_NAME="$PHANTOM_DIR_NAME.tar.bz2"
PHANTOM_URL="https://bitbucket.org/ariya/phantomjs/downloads/$PHANTOM_FILE_NAME"

mkdir -p ./libs
if [[ ! -d ./libs/$PHANTOM_DIR_NAME ]]; then
    wget $PHANTOM_URL
    tar -xvjf $PHANTOM_FILE_NAME -C ./libs
fi
[[ -h phantomjs ]] && unlink phantomjs
ln -s ./libs/$PHANTOM_DIR_NAME phantomjs

SELENIUM_VERSION='2.43'
SELENIUM_RELEASE_VERSION="$SELENIUM_VERSION.0"
SELENIUM_JAR="selenium-server-standalone-$SELENIUM_RELEASE_VERSION.jar"
SELENIUM_URL="http://selenium-release.storage.googleapis.com/$SELENIUM_VERSION/$SELENIUM_JAR"

if [[ ! -f ./libs/$SELENIUM_JAR ]]; then
    wget $SELENIUM_URL
    mv $SELENIUM_JAR ./libs/
fi

[[ -h selenium ]] && unlink selenium
ln -s ./libs/$SELENIUM_JAR selenium

mkdir -p ./libs/selenium
SELENIUM_NODE_CONFIG_FILE="./libs/selenium/nodeConfig.json"
SELENIUM_NODE_CONFIG='{"capabilities":[{"browserName":"phantomjs","maxInstances": 4}]}'

if [[ ! -f $SELENIUM_NODE_CONFIG_FILE ]]; then
    echo  $SELENIUM_NODE_CONFIG > $SELENIUM_NODE_CONFIG_FILE
fi
