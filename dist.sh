#!/bin/sh
# Baut ein eigenständiges Programm mit mitgelieferter Java-Laufzeit (jlink + jpackage, beides im JDK enthalten).
# Aufruf: ./dist.sh [Version]   (z.B. 1.2.0 oder v1.2.0; Standard 1.0.0)
# Ergebnis in target/dist/:
#   Windows: beswinged-<Version>-windows-x64.zip   (Ordner mit beswinged.exe)
#   Linux:   beswinged-<Version>-linux-x64.tar.gz  (Ordner mit bin/beswinged)
#   macOS:   beswinged-<Version>-macos-<arch>.tar.gz (beswinged.app)
# Benötigt JDK 21+ im PATH (z.B. Eclipse Temurin). Unter Windows in Git Bash ausführen.
set -e
cd "$(dirname "$0")"

VERSION="${1:-1.0.0}"
VERSION="${VERSION#v}"
# jpackage verlangt eine rein numerische Version (höchstens drei Teile)
APP_VERSION=$(echo "$VERSION" | sed 's/[^0-9.].*//' | cut -d. -f1-3)
[ -n "$APP_VERSION" ] || APP_VERSION=1.0.0

case "$(uname -s)" in
  MINGW*|MSYS*|CYGWIN*) OS=windows ;;
  Darwin) OS=macos ;;
  *) OS=linux ;;
esac
case "$(uname -m)" in
  arm64|aarch64) ARCH=aarch64 ;;
  *) ARCH=x64 ;;
esac

if [ ! -f target/beswinged.jar ]; then
  if command -v mvn >/dev/null 2>&1; then mvn -B -q package; else ./build.sh; fi
fi

rm -rf target/runtime target/app target/dist target/jpackage-input
mkdir -p target/jpackage-input target/dist
cp target/beswinged.jar target/jpackage-input/

# Laufzeit nur mit den benötigten Modulen (siehe: jdeps --print-module-deps target/beswinged.jar)
jlink --add-modules java.base,java.desktop \
      --strip-debug --no-header-files --no-man-pages --compress=zip-6 \
      --output target/runtime

jpackage --type app-image --name beswinged --app-version "$APP_VERSION" \
         --vendor "xycle1smurf" \
         --description "JSON-Lines-Log-Viewer" \
         --input target/jpackage-input --main-jar beswinged.jar --main-class jsonlogviewer.JsonLogViewer \
         --runtime-image target/runtime \
         --java-options "-XX:MaxRAMPercentage=50" \
         --dest target/app

NAME="beswinged-$VERSION-$OS-$ARCH"
if [ "$OS" = windows ]; then
  # jar erzeugt ein normales ZIP und ist überall vorhanden, wo ein JDK ist
  (cd target/app && jar --create --no-manifest --file "../dist/$NAME.zip" beswinged)
else
  tar -C target/app -czf "target/dist/$NAME.tar.gz" "$(ls target/app)"
fi
ls -l target/dist
echo "Fertig: target/dist/$NAME.*"
