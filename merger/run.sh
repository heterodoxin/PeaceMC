#!/bin/bash
# CLI merge using the standalone Merger.jar: seedjar peacejar mapping [config] -> bundle/Core.jar
exec java -cp "$(dirname "$0")/Merger.jar" Merge "$@"