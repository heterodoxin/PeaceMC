#!/bin/bash
# Automated transfer pipeline test - no Minecraft, no Gradle, no netty.
# Compiles the exact Wire/Frame/Crypto classes the mod and plugin ship with,
# then runs a client<->server<->client round trip at the byte level.
set -euo pipefail
cd "$(dirname "$0")"

JAVAC="$(command -v javac || echo /var/home/Heterodoxin/minecraft/jdk-25/bin/javac)"
JAVA="$(command -v java || echo /var/home/Heterodoxin/minecraft/jdk-25/bin/java)"
CORE="../core/src/main/java"

rm -rf work
mkdir -p work
"$JAVAC" -d work -sourcepath "$CORE:src" src/dev/peace/wiretest/Main.java
"$JAVA" -cp work dev.peace.wiretest.Main