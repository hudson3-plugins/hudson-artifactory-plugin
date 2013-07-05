#!/bin/bash

set -x

mvn -e -B clean install -s ~/Projects/maven-plugins/settings.xml
rm -rf ~/.hudson/plugins/artifactory/ ~/.hudson/plugins/artifactory.hpi
cp target/artifactory.hpi ~/.hudson/plugins/ 
rm -rf ~/Temp
mkdir  ~/Temp
unzip -q target/artifactory.hpi -d ~/Temp
