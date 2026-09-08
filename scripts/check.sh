#!/bin/sh
# Run from the repository root. Compile tests without contacting the Nearz API.
set -eu
git diff --check
mvn -B -DskipTests test-compile
