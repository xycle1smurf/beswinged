#!/bin/sh
# Baut jsonlogviewer.jar (benötigt JDK 21+ im PATH)
set -e
cd "$(dirname "$0")"
rm -rf out
javac --release 21 -encoding UTF-8 -d out src/jsonlogviewer/*.java
jar --create --file jsonlogviewer.jar --main-class jsonlogviewer.JsonLogViewer -C out .
echo "Fertig: jsonlogviewer.jar"
