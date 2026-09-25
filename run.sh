#!/bin/sh
# Builds CkShaderStudio if needed and runs it.
cd "$(dirname "$0")" || exit 1
JAR=target/ckshaderstudio-0.1.0-jar-with-dependencies.jar
if [ ! -f "$JAR" ] || [ -n "$(find src pom.xml -newer "$JAR" -type f 2>/dev/null | head -1)" ]; then
  mvn -q package || exit 1
fi
exec java -jar "$JAR" "$@"
