#!/usr/bin/env bash
# SmartQueue - compile and start (Linux / macOS)
# Usage:  ./scripts/run.sh            (build + run)
#         ./scripts/run.sh --no-build (run the last build)
set -e
cd "$(dirname "$0")/.."

if ! command -v javac >/dev/null 2>&1; then
  echo "ERROR: JDK 11 or newer is required (javac not found). Install it and try again." >&2
  exit 1
fi

if [ "$1" != "--no-build" ]; then
  echo "Compiling..."
  mkdir -p out
  javac --release 11 -encoding UTF-8 -sourcepath backend/src -d out backend/src/com/smartqueue/Main.java
fi

CP="out"
for jar in lib/*.jar; do
  [ -e "$jar" ] && CP="$CP:$jar"
done

if ! ls lib/*.jar >/dev/null 2>&1; then
  echo "NOTE: lib/ is empty. Put the Oracle JDBC driver (ojdbc8.jar) in lib/ (see lib/README.txt) or the database connection will fail."
fi

exec java -cp "$CP" com.smartqueue.Main
