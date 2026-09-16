#!/usr/bin/env bash
# Runs inside the harness container. Clean-clones the given tag from GitHub with
# submodules (so PonyDirect-Kotlin is fetched at its pinned commit), drops in the
# signing material, and builds the signed foss release APK. The APK and its
# sha256 land in /output.
set -euo pipefail

TAG="${1:?usage: docker run ... <tag>   e.g. v3.0.0}"
REPO="${CARRIERPONY_REPO:-https://github.com/norsehorse-dev/CarrierPonyAndroid.git}"
SRC=/build/src

rm -rf "$SRC"
git clone --branch "$TAG" --depth 1 --recurse-submodules "$REPO" "$SRC"

# Signing material is bind-mounted read-only at /secrets. storeFile in
# keystore.properties is repo-root-relative (carrierpony-release.jks), so both
# files go at the clone root where build.gradle.kts resolves them.
cp /secrets/keystore.properties "$SRC/keystore.properties"
cp /secrets/carrierpony-release.jks "$SRC/carrierpony-release.jks"

cd "$SRC"
# Cap the JVM so R8 plus the build cannot overcommit and trip the OOM-killer
# under amd64 emulation ("Gradle build daemon disappeared").
export GRADLE_OPTS="-Dorg.gradle.jvmargs=-Xmx6g -Dorg.gradle.workers.max=2"
./gradlew --no-daemon --no-parallel clean :app:assembleFossRelease

APK=$(ls app/build/outputs/apk/foss/release/app-foss-release*.apk | head -1)
OUT="/output/carrierpony-${TAG#v}.apk"
mkdir -p /output
cp "$APK" "$OUT"
echo "----"
sha256sum "$OUT"
echo "Built $OUT"
