#!/bin/sh
# 안티치트 엔진 단위 테스트 — 마크 없이 순수 자바로 돈다. 사용: tools/anticheat-test/run.sh [JDK 경로]
set -e
JDK=${1:-$JAVA_HOME}
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
OUT=$(mktemp -d)
"$JDK/bin/javac" -d "$OUT" $(find "$ROOT/src/client/java/kfc/udp/client/anticheat" "$ROOT/tools/anticheat-test" -name '*.java')
"$JDK/bin/java" -Dstdout.encoding=UTF-8 -cp "$OUT" kfc.udp.client.anticheat.AcTest
