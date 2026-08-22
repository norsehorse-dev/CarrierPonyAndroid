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

plugins {
    alias(libs.plugins.kotlin.jvm)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    api("org.bouncycastle:bcprov-jdk18on:1.84")
    api("org.bouncycastle:bcpg-jdk18on:1.84")

    testImplementation("junit:junit:4.13.2")
}

tasks.test {
    useJUnit()
    testLogging { events("passed", "skipped", "failed") }
}
