#!/bin/bash
# Usage: bash scripts/javac_check.sh [worktree-dir] [maxlines]
# Compiles src/main/java with javac -proc:none (no Gradle), then compiles src/test/java and runs the pure-Java unit tests.
# Needs the local JDK under .local/tools/java, the JUnit console jar under .local/tools/junit, and the NeoForge merged jar
# plus dependency jars that an earlier Gradle sync left in build/moddev and .local/gradle-home of the main checkout.
R=$(cd "$(dirname "$0")/.." && git rev-parse --path-format=absolute --git-common-dir | sed 's#/\.git$##')
W=$(cd "${1:-$R}" && pwd)
TAG=$(basename "$W")
OUT=$R/.local/tmp/javac-$TAG
mkdir -p "$R/.local/tmp"
JAVAC=$(find "$R/.local/tools/java" -name javac.exe -o -name javac -type f 2>/dev/null | head -1)
JAVA=$(dirname "$JAVAC")/java
JUNIT=$(find "$R/.local/tools/junit" -name 'junit-platform-console-standalone-*.jar' | head -1)
GSON=$(find "$R/.local/gradle-home" -name 'gson-2.10.1.jar' | head -1)
M=$(cygpath -m "$R" 2>/dev/null || echo "$R")
CP="$M/build/moddev/artifacts/neoforge-21.1.250-merged.jar"
for j in $(find "$R/.local/gradle-home" -name '*.jar' 2>/dev/null); do CP="$CP;$(cygpath -m "$j" 2>/dev/null || echo "$j")"; done
winlist() { find "$1" -name '*.java' | while read -r f; do echo "\"$(cygpath -m "$f" 2>/dev/null || echo "$f")\""; done; }
mp() { cygpath -m "$1" 2>/dev/null || echo "$1"; }
echo "-cp \"$CP\"" > "$OUT.args"
winlist "$W/src/main/java" > "$OUT.src"
rm -rf "$OUT" "$OUT-test"; mkdir -p "$OUT" "$OUT-test"
"$JAVAC" -proc:none -nowarn -encoding UTF-8 -Xmaxerrs 300 -d "$(mp "$OUT")" @"$(mp "$OUT.args")" @"$(mp "$OUT.src")" 2>&1 | grep -av "^Note:" | head -"${2:-80}"
RC=${PIPESTATUS[0]}
if [ -d "$W/src/test/java" ] && [ "$RC" = 0 ]; then
  winlist "$W/src/test/java" > "$OUT.tsrc"
  "$JAVAC" -proc:none -nowarn -encoding UTF-8 -cp "$(mp "$OUT");$(mp "$JUNIT");$(mp "$GSON")" -d "$(mp "$OUT-test")" @"$(mp "$OUT.tsrc")" 2>&1 | grep -av "^Note:" | head -40
  TRC=${PIPESTATUS[0]}
  echo "test javac exit=$TRC"
  # Tests run WITHOUT Minecraft on the classpath, like CI: a test that loads a Minecraft class fails here too.
  RC=$TRC
  if [ "$TRC" = 0 ]; then
  "$JAVA" -jar "$(mp "$JUNIT")" execute -cp "$(mp "$OUT");$(mp "$OUT-test");$(mp "$GSON");$(mp "$W/src/main/resources")" \
    --select-package io.github.longshou.mcyukkuri.motor.geom --select-package io.github.longshou.mcyukkuri.intent \
    --details=tree --disable-banner --disable-ansi-colors 2>&1 | grep -aE "tests (successful|failed|found)|✘|\[X\]|expected|Exception" | head -30
  RC=${PIPESTATUS[0]}
  fi
fi
rm -rf "$OUT" "$OUT-test" "$OUT.args" "$OUT.src" "$OUT.tsrc"
echo "javac exit=$RC"
exit "$RC"
