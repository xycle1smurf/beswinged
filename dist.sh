#!/bin/sh
# Baut ein eigenständiges Programm mit mitgelieferter Java-Laufzeit (jlink + jpackage, beides im JDK enthalten).
# Aufruf: ./dist.sh [Version]   (z.B. 1.2.0 oder v1.2.0; Standard: <revision> aus pom.xml)
# Ergebnis in target/dist/:
#   Windows: beswinged-<Version>-windows-x64.zip        (Ordner mit beswinged.exe)
#            beswinged-<Version>-windows-x64-setup.exe  (NSIS-Installer, wenn NSIS installiert ist)
#   Linux:   beswinged-<Version>-linux-x64.tar.gz       (Ordner mit bin/beswinged)
#   macOS:   beswinged-<Version>-macos-<arch>.tar.gz    (beswinged.app)
# Benötigt JDK 21+ im PATH (z.B. Eclipse Temurin). Unter Windows in Git Bash ausführen.
set -e
cd "$(dirname "$0")"

VERSION="${1:-$(sed -n 's:.*<revision>\(.*\)</revision>.*:\1:p' pom.xml)}"
VERSION="${VERSION#v}"
# jpackage und NSIS verlangen eine rein numerische Version (höchstens drei Teile)
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

# Jar mit Versionsangabe bauen (Hilfe → Über zeigt sie an)
if command -v mvn >/dev/null 2>&1; then mvn -B -q package -Drevision="$VERSION"; else ./build.sh; fi

rm -rf target/runtime target/app target/dist target/jpackage-input target/installer
mkdir -p target/jpackage-input target/dist target/installer
cp target/beswinged.jar target/jpackage-input/

# Programm-Icon je Betriebssystem
case "$OS" in
  windows) ICON=packaging/beswinged.ico ;;
  linux)   ICON=src/main/resources/jsonlogviewer/icons/icon-256.png ;;
  macos)
    set_dir=target/installer/beswinged.iconset
    mkdir -p "$set_dir"
    for s in 16 32 128 256 512; do
      sips -z $s $s packaging/beswinged.png --out "$set_dir/icon_${s}x${s}.png" >/dev/null
      sips -z $((s * 2)) $((s * 2)) packaging/beswinged.png --out "$set_dir/icon_${s}x${s}@2x.png" >/dev/null
    done
    iconutil -c icns "$set_dir" -o target/installer/beswinged.icns
    ICON=target/installer/beswinged.icns ;;
esac

# Laufzeit nur mit den benötigten Modulen (siehe: jdeps --print-module-deps target/beswinged.jar)
jlink --add-modules java.base,java.desktop,java.xml \
      --strip-debug --no-header-files --no-man-pages --compress=zip-6 \
      --output target/runtime

jpackage --type app-image --name beswinged --app-version "$APP_VERSION" \
         --vendor "xycle1smurf" \
         --description "JSON-Lines-Log-Viewer" \
         --icon "$ICON" \
         --input target/jpackage-input --main-jar beswinged.jar --main-class jsonlogviewer.JsonLogViewer \
         --runtime-image target/runtime \
         --java-options "-XX:MaxRAMPercentage=50" \
         --dest target/app

NAME="beswinged-$VERSION-$OS-$ARCH"
if [ "$OS" = windows ]; then
  # jar erzeugt ein normales ZIP und ist überall vorhanden, wo ein JDK ist
  (cd target/app && jar --create --no-manifest --file "../dist/$NAME.zip" beswinged)

  MAKENSIS=$(command -v makensis 2>/dev/null || true)
  for p in "/c/Program Files (x86)/NSIS/makensis.exe" "/c/Program Files/NSIS/makensis.exe"; do
    [ -n "$MAKENSIS" ] || { [ -x "$p" ] && MAKENSIS="$p"; } || true
  done
  if [ -n "$MAKENSIS" ]; then
    # Liesmich und Lizenz als eine Seite: UTF-8 mit BOM und CRLF, wie es die Lizenzseite von NSIS erwartet
    LIC=target/installer/Liesmich-und-Lizenz.txt
    {
      printf '\357\273\277'
      printf 'beswinged %s\n================\n\n' "$VERSION"
      cat README.md
      printf '\n\n=====================================================================\n'
      printf 'LIZENZ\n=====================================================================\n\n'
      cat LICENSE
    } | sed 's/\r$//; s/$/\r/' > "$LIC"
    "$MAKENSIS" -V2 -INPUTCHARSET UTF8 \
      "-DVERSION=$APP_VERSION" \
      "-DAPPDIR=$(cygpath -w "$PWD/target/app/beswinged")" \
      "-DLICENSEFILE=$(cygpath -w "$PWD/$LIC")" \
      "-DICON=$(cygpath -w "$PWD/packaging/beswinged.ico")" \
      "-DOUTFILE=$(cygpath -w "$PWD/target/dist/$NAME-setup.exe")" \
      "$(cygpath -w "$PWD/packaging/windows/beswinged.nsi")"
  else
    echo "Hinweis: NSIS (makensis) nicht gefunden, Installer wird übersprungen."
  fi
else
  tar -C target/app -czf "target/dist/$NAME.tar.gz" "$(ls target/app)"
fi
ls -l target/dist
echo "Fertig: target/dist/$NAME*"
