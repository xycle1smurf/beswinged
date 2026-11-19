#!/bin/sh
# Baut target/beswinged.jar ohne Maven (benötigt JDK 21+ im PATH).
# Mit Maven: mvn package
set -e
cd "$(dirname "$0")"
rm -rf target
javac --release 21 -encoding UTF-8 -d target/classes src/main/java/jsonlogviewer/*.java
jar --create --file target/beswinged.jar --main-class jsonlogviewer.JsonLogViewer -C target/classes .
echo "Fertig: target/beswinged.jar"
