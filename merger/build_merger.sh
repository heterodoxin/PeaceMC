#!/bin/bash
# Bundles the Swing GUI, ASM, and the obfuscated peace plugin + mapping into a standalone Merger.jar.
set -euo pipefail
cd "$(dirname "$0")"

JAVAC="$(command -v javac || echo /var/home/Heterodoxin/minecraft/jdk-25/bin/javac)"
JAR="$(command -v jar || echo /var/home/Heterodoxin/minecraft/jdk-25/bin/jar)"

ASM="${ASM:-}"
if [[ -z "$ASM" ]]; then
  ASM="$(find "$HOME/.gradle/caches" -path '*org.ow2.asm/asm/9.10.1*' -name 'asm-9.10.1.jar' 2>/dev/null | head -1 || true)"
fi
if [[ -z "$ASM" || ! -s "$ASM" ]]; then
  echo "ASM 9.10.1 jar not found. Put the path in \$ASM." >&2
  exit 1
fi

PLUGIN_OUT="${PLUGIN_OUT:-../plugin/build/libs}"
mkdir -p res/lib
cp "$PLUGIN_OUT/Peace.jar" res/lib/peace.jar
cp "$PLUGIN_OUT/mapping.txt" res/lib/mapping.txt

rm -rf work
mkdir -p work/classes work/fat/lib
"$JAVAC" -cp "$ASM" -d work/classes Merge.java MergerGui.java
(cd work/fat && "$JAR" xf "$ASM")
cp -r work/classes/* work/fat/
cp res/lib/* work/fat/lib/
printf 'Main-Class: MergerGui\n' > work/manifest
"$JAR" --create --file Merger.jar --manifest work/manifest -C work/fat .
rm -rf work
echo "built Merger.jar"
ls -la Merger.jar