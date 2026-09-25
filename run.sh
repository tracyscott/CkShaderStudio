#!/bin/sh
# Builds CkShaderStudio if needed and runs it.
cd "$(dirname "$0")" || exit 1
# The project version is the first <version> in pom.xml.
VERSION=$(sed -n 's:.*<version>\(.*\)</version>.*:\1:p' pom.xml | head -1)
JAR="target/ckshaderstudio-$VERSION-jar-with-dependencies.jar"
if [ ! -f "$JAR" ] || [ -n "$(find src pom.xml -newer "$JAR" -type f 2>/dev/null | head -1)" ]; then
  mvn -q package || exit 1
fi
exec java -jar "$JAR" "$@"
