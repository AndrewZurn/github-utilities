#!/usr/bin/env bash
set -ex

pushd ..

./gradlew clean packageDistribution

$GRAALVM_HOME/bin/native-image --no-fallback -cp ./dist/lib/*.jar \
  -H:Name=github-utilities-native \
  -H:Class=MainKt \
  -H:+ReportUnsupportedElementsAtRuntime \
  -H:EnableURLProtocols=https \
  -H:ReflectionConfigurationFiles=./graal/reflectconfig.json

mv github-utilities-native graal

popd
