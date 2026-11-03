// CarrierPonyCore (Android) — the auditable crypto core of CarrierPony.
//
// A pure Kotlin/JVM library wrapping BouncyCastle for the small, fixed set of
// OpenPGP operations the messenger needs: v4 Ed25519+Cv25519 identity
// generation, armored detached signatures for relay challenge auth, and
// sign-and-encrypt / decrypt-and-verify for message envelopes. No Android, no
// Compose, no Context — it builds and tests on a plain JDK.
//
// This is a deliberate sibling of PGPonyCore-Kotlin, not a dependency on it:
// the two apps' cores stay independent, exactly as CarrierPony iOS links its
// own local PGPonyCore rather than the published package. Where BouncyCastle
// usage overlaps (key generation, SEIPD handling, issuer-fingerprint
// subpackets), the patterns here mirror the ones proven in PGPony Android.

import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
}

java {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
}

// No kotlin { jvmToolchain(17) }: F-Droid's buildserver runs with Gradle
// toolchain auto-provisioning disabled, so a jvmToolchain() request fails hard
// there. sourceCompatibility/targetCompatibility above plus jvmTarget here emit
// JVM 8 bytecode with no toolchain discovery step. Mirrors PonyDirect-Kotlin.
kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_1_8)
        // Compile against the Java 8 API only. This library runs on Android, where
        // methods added in Java 9+ (ByteArrayOutputStream.toString(Charset),
        // BigInteger.TWO, ...) only exist from Android 13 and crash older phones.
        // Lint can't see into this module, so the compiler has to refuse them.
        freeCompilerArgs.add("-Xjdk-release=1.8")
    }
}

dependencies {
    api("org.bouncycastle:bcprov-jdk18on:1.85")
    api("org.bouncycastle:bcpg-jdk18on:1.85")

    testImplementation("junit:junit:4.13.2")
}

tasks.test {
    useJUnit()
    testLogging { events("passed", "skipped", "failed") }
}
