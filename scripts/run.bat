@echo off
rem SmartQueue - compile and start (Windows)
cd /d "%~dp0\.."

where javac >nul 2>nul
if errorlevel 1 (
  echo ERROR: JDK 11 or newer is required ^(javac not found^). Install it and try again.
  pause
  exit /b 1
)

if not exist out mkdir out
echo Compiling...
javac --release 11 -encoding UTF-8 -sourcepath backend\src -d out backend\src\com\smartqueue\Main.java
if errorlevel 1 (
  echo Compilation failed.
  pause
  exit /b 1
)

if not exist lib\*.jar echo NOTE: lib\ is empty. Put the Oracle JDBC driver ^(ojdbc8.jar^) in lib\ ^(see lib\README.txt^).

java -cp "out;lib\*" com.smartqueue.Main
pause
