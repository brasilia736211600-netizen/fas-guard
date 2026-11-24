#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$ROOT/build/engine-test"
rm -rf "$OUT"
mkdir -p "$OUT"
javac -encoding UTF-8 -d "$OUT" \
  "$ROOT/app/src/main/java/com/fas/guard/net/Net.java" \
  "$ROOT/app/src/main/java/com/fas/guard/net/TcpRelay.java" \
  "$ROOT/app/src/main/java/com/fas/guard/net/DnsCache.java" \
  "$ROOT/tools/NetTest.java" \
  "$ROOT/tools/TcpRelayTest.java" \
  "$ROOT/tools/DnsCacheTest.java"
java -cp "$OUT" NetTest
java -cp "$OUT" TcpRelayTest
java -cp "$OUT" DnsCacheTest
rm -rf "$OUT"
echo "ENGINE TESTS OK"
