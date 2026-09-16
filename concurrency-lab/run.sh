#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────────
#  run.sh — compile and run ONE file of this lab with plain javac/java.
#
#  No Maven. javac -sourcepath pulls in only the other sources the file
#  actually references (support/, api/, …), so a run costs ~1s.
#
#      ./run.sh src/main/java/com/locallearn/concurrency/exercises/Ex01Counter.java
#      ./run.sh Ex01Counter            # short form: matched by class name
#
#  VS Code's Code Runner is wired to this script for every *.java in this
#  module (see .vscode/settings.json), so Ctrl/Cmd+Alt+N on an exercise or a
#  demo just works.
# ─────────────────────────────────────────────────────────────────────────────
set -euo pipefail

cd "$(dirname "$0")"

SRC=src/main/java
OUT=build/classes

arg="${1:-}"
if [ -z "$arg" ]; then
    echo "usage: ./run.sh <file.java | ClassName>" >&2
    exit 2
fi

# Short form: ./run.sh Ex01Counter — find the single source file with that name.
if [ ! -f "$arg" ]; then
    matches=$(find "$SRC" -name "${arg%.java}.java")
    count=$(printf '%s' "$matches" | grep -c . || true)
    if [ "$count" -eq 0 ]; then
        echo "run.sh: no source file named '${arg%.java}.java' under $SRC" >&2
        exit 2
    elif [ "$count" -gt 1 ]; then
        echo "run.sh: '$arg' is ambiguous:" >&2
        echo "$matches" >&2
        exit 2
    fi
    arg="$matches"
fi

mkdir -p "$OUT"
javac -d "$OUT" -sourcepath "$SRC" "$arg"

package=$(sed -n 's/^package \([a-zA-Z0-9_.]*\);.*/\1/p' "$arg" | head -1)
class=$(basename "$arg" .java)
main="${package:+$package.}$class"

if ! grep -q 'static void main' "$arg"; then
    echo "run.sh: $class has no main method — compiled it, nothing to run." >&2
    exit 0
fi

echo "── running $main ──"
exec java -ea -cp "$OUT" "$main" "${@:2}"
