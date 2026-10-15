Put the Oracle JDBC driver here
===============================

SmartQueue needs the official Oracle JDBC driver ("ojdbc") to talk to Oracle Database.
It is a single .jar file - the project itself has no other dependencies.
(Oracle's licence does not allow us to ship the file inside this project, so you
 download it once - it is free.)

1. Download ONE of these:
     * ojdbc8.jar   (recommended - works with Java 8 / 11 / 17 / 21 and Oracle 11g XE ... 23ai)
         https://www.oracle.com/database/technologies/appdev/jdbc-downloads.html
     * or from Maven Central (no login needed):
         https://repo1.maven.org/maven2/com/oracle/database/jdbc/ojdbc8/
         (take the newest folder, e.g. 19.x or 21.x, and download  ojdbc8-<version>.jar)
     * ojdbc11.jar also works if you run Java 11 or newer.
2. Copy the jar into this folder (lib/).   Example:   lib/ojdbc8-21.9.0.0.jar
3. Start the application with scripts/run.sh (Linux/macOS) or scripts\run.bat (Windows).

Only ONE ojdbc jar may be present at a time. Do not rename the file's extension.
