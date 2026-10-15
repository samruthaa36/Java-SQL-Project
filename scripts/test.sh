#!/usr/bin/env bash
# Runs the end-to-end API test-suite against a running server on a FRESH database.
# Usage: ./scripts/test.sh [http://localhost:8080] [--slow]
cd "$(dirname "$0")/.."
BASE="${1:-http://localhost:8080}"
shift 2>/dev/null || true
exec python3 tests/api_test.py "$BASE" "$@"
