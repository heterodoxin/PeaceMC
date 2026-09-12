#!/bin/bash
# CLI merge using the standalone Injector.jar: seedjar peacejar mapping [config] -> bundle/Core.jar
exec java -cp "$(dirname "$0")/Injector.jar" Inject "$@"