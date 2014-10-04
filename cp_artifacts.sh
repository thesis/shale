#!/usr/bin/env bash
for jar in `find target | grep "\.jar$" | grep -i shale`; do
    cp $jar $1;
done
