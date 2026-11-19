@echo off
rem Baut jsonlogviewer.jar (benoetigt JDK 21+ im PATH)
setlocal
cd /d "%~dp0"
if exist out rmdir /s /q out
javac --release 21 -encoding UTF-8 -d out src\jsonlogviewer\*.java || exit /b 1
jar --create --file jsonlogviewer.jar --main-class jsonlogviewer.JsonLogViewer -C out . || exit /b 1
echo Fertig: jsonlogviewer.jar
