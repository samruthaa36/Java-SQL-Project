#!/usr/bin/env bash
# Compile only (Linux / macOS). Output goes to ./out
set -e
cd "$(dirname "$0")/.."
mkdir -p out
javac --release 11 -encoding UTF-8 -sourcepath backend/src -d out backend/src/com/smartqueue/Main.java
echo "Build OK  ->  out/"
