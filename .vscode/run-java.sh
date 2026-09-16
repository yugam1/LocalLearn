#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────────
#  Code Runner dispatcher for Java files in this workspace.
#
#  Why a script instead of code-runner.executorMapByGlob: that setting matches
#  its globs against the FILE NAME only (codeManager.js does
#  micromatch.isMatch(basename(file), glob)), never the directory, so a pattern
#  like "**/concurrency-lab/**/*.java" can never match "Ex04Worker.java". This
#  dispatcher looks at the real path instead.
# ─────────────────────────────────────────────────────────────────────────────
set -euo pipefail

file="${1:?usage: run-java.sh <File.java>}"
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

case "$file" in
    */concurrency-lab/*)
        # Multi-package lab: compile the file plus whatever it references.
        exec "$root/concurrency-lab/run.sh" "$file"
        ;;
    */order-service/*)
        echo "order-service is a Spring Boot app — running a single file will not start it."
        echo "  Run and Debug → 'order-service' (F5)"
        echo "  or: cd order-service && ./mvnw spring-boot:run"
        exit 1
        ;;
esac

# Anything else: a standalone file, via the JDK's single-file source mode.
exec java -ea "$file"
