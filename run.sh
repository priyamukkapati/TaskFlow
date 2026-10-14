#!/usr/bin/env sh
set -eu
cd "$(dirname "$0")"
mkdir -p build
java -m jdk.compiler/com.sun.tools.javac.Main --release 17 -d build src/*.java tests/*.java
case "${1:-demo}" in
  test) java -ea -cp build TaskFlowTest ;;
  demo) java -ea -cp build Demo ;;
  serve) java -cp build TaskFlow ;;
  cli) shift; java -cp build TaskFlow "$@" ;;
  *) echo "Usage: sh run.sh [test|demo|serve|cli ...]"; exit 2 ;;
esac
