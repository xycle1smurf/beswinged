@echo off
rem Baut target\beswinged.jar ohne Maven (benoetigt JDK 21+ im PATH).
rem Mit Maven: mvn package
setlocal
cd /d "%~dp0"
if exist target rmdir /s /q target
javac --release 21 -encoding UTF-8 -d target\classes src\main\java\jsonlogviewer\*.java || exit /b 1
xcopy /e /i /q /y src\main\resources target\classes >nul || exit /b 1
mkdir target\classes\META-INF 2>nul
copy /y LICENSE target\classes\META-INF\LICENSE >nul || exit /b 1
jar --create --file target\beswinged.jar --main-class jsonlogviewer.JsonLogViewer -C target\classes . || exit /b 1
echo Fertig: target\beswinged.jar
