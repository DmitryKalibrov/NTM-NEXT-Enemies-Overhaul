#!/bin/sh
# Checks the Java Tatterwing animation against the Blender preview. Build first (gradlew build).
cd "$(dirname "$0")/.."
JOML=$(cygpath -w "$(find /e/DevData/Gradle/.gradle/caches/modules-2 -name 'joml-*.jar' | grep -v sources | sort | tail -1)")
CP="fabric/build/classes/java/main;$JOML"
JDK="/c/Program Files/Eclipse Adoptium/jdk-25.0.2.10-hotspot/bin"
mkdir -p build/check
"$JDK/javac" -cp "$CP" -d build/check tools/TatterwingPoseCheck.java && "$JDK/java" -cp "build/check;$CP" TatterwingPoseCheck
