#!/usr/bin/env bash

rm shinycannon_*
docker run -v `pwd`:/mnt -it appsilon/shinycannon:1.0.0 bash -c "cd /mnt; make packages"
source clear.sh

