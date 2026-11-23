#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
classes="$(mktemp -d)"
trap 'rm -rf "$classes"' EXIT
if [[ -n "${ECJ_JAR:-}" ]]; then
  compiler=(java -jar "$ECJ_JAR")
  flags=(-17 -failOnWarning)
elif command -v javac >/dev/null; then
  compiler=(javac)
  flags=(--release 17 -Xlint:all -Werror)
else
  compiler=(java -m jdk.compiler/com.sun.tools.javac.Main)
  flags=(--release 17 -Xlint:all -Werror)
fi
find src/main/java/dev/societybots/core src/test/java -name '*.java' -print0 | xargs -0 "${compiler[@]}" "${flags[@]}" -d "$classes"
java -cp "$classes" dev.societybots.core.Checks
