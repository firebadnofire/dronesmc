#!/usr/bin/env bash
set -euo pipefail
java -version 2>&1 | grep -E 'version "1\.8\.'
test -x "$JAVA_HOME/bin/javac"
# Windows checkouts may carry CRLF; invoke a normalized copy in the same directory.
sed 's/\r$//' gradlew > .ci-gradlew
trap 'rm -f .ci-gradlew' EXIT
bash .ci-gradlew --version | tee gradle-version.log
grep -F 'Gradle 4.9' gradle-version.log
env -u DRONESMC_BUILD_DIR bash .ci-gradlew clean build --no-daemon --console=plain 2>&1 | tee gradle-build.log
grep -E '^(> Task )?:reobfJar( |$)' gradle-build.log
python3 scripts/ci/artifact.py stage ci-dist
