# Reproducible build harness (foss / F-Droid)

CarrierPony's foss release APK is built here on Linux so it byte-matches
F-Droid's clean-room rebuild. Pure Kotlin/JVM app, so this is just Ubuntu +
Temurin JDK 17 + the Android SDK, with none of the Rust/NDK machinery the
PassPony and VaultPony harnesses need.

## Build

Build the image as amd64 to match F-Droid's buildserver (Rosetta handles it on
Apple Silicon):

```
docker build --platform linux/amd64 -t carrierpony-fdroid reproducible
```

## Cut a release

Tag and push first, then build that tag. The keystore and keystore.properties
never enter the image; they are bind-mounted read-only:

```
docker run --rm --platform linux/amd64 -v /ABSOLUTE/PATH/TO/signing:/secrets:ro -v "$PWD/reproducible/out":/output carrierpony-fdroid v3.0.0
```

The `/secrets` directory must hold `keystore.properties` and
`carrierpony-release.jks`. The signed APK lands at
`reproducible/out/carrierpony-3.0.0.apk`; attach it to the GitHub release for
F-Droid's comparison.

## If R8 gets OOM-killed

"Gradle build daemon disappeared" under emulation is the kernel OOM-killer, not
a crash. Raise Docker Desktop memory (12 GB holds; 8 GB dies mid-R8). The
GRADLE_OPTS cap in build.sh already limits the JVM.

## Version knobs

Override as build args if F-Droid's build log shows different components:

```
--build-arg CMDLINE_TOOLS_VERSION=<current>   # from developer.android.com
--build-arg BUILD_TOOLS_VERSION=36.0.0
--build-arg PLATFORM=android-36
```

## Before publishing

Verify the APK: baseline profile absent (`unzip -l ... | grep -i baseline`), no
host paths leaked, and cert SHA-256 matches the recipe's AllowedAPKSigningKeys.
