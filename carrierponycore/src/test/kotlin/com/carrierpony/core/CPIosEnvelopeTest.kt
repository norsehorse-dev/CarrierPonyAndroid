// CPIosEnvelopeTest.kt
// CarrierPonyCore
//
// Envelopes traded with the iOS core, in every pairing of v4 and v6 that
// involves a v6 identity. Each file's plaintext is its own name.
//
//   peer-android-*            two identities made here, for the iOS tests to
//                             write to and read from
//   android-*-to-ios-*.b64    written here, opened by the iOS tests
//   ios-*-to-android-*.b64    written by the iOS tests
//                             (SharedEnvelopeTests in the iOS project), opened here
//
// emitPeerFixturesForIos only runs with CP_EMIT_IOS_FIXTURES=1, because making
// new peer identities orphans the envelopes iOS already wrote for the old ones.
// All the keys belong to nobody.

package com.carrierpony.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.Base64

class CPIosEnvelopeTest {

    private class Party(val fingerprint: String, val secretKey: ByteArray, val publicKey: ByteArray)

    private fun exists(name: String): Boolean = javaClass.getResource("/ios/$name") != null
    private fun resource(name: String): ByteArray = javaClass.getResourceAsStream("/ios/$name")!!.use { it.readBytes() }
    private fun text(name: String): String = String(resource(name), Charsets.UTF_8)
    private fun base64(name: String): ByteArray = Base64.getDecoder().decode(text(name).filterNot { it.isWhitespace() })

    private fun ios(version: String) = Party(
        text("ios-$version-fingerprint.txt").trim(),
        resource("ios-$version-secret.asc"),
        CPArmor.dearmor(text("ios-$version-public.asc"))!!
    )

    private fun android(version: String) = Party(
        text("peer-android-$version-fingerprint.txt").trim(),
        base64("peer-android-$version-secret.b64"),
        CPArmor.dearmor(text("peer-android-$version-public.asc"))!!
    )

    @Test
    fun emitPeerFixturesForIos() {
        assumeTrue(System.getenv("CP_EMIT_IOS_FIXTURES") == "1")
        val directory = File("src/test/resources/ios")
        assertTrue("run from the carrierponycore module", directory.isDirectory)
        val encoder = Base64.getEncoder()

        val peers = mapOf(
            "v4" to CPIdentityGenerator.generateV4Identity("Android Fixture", "fixture@carrierpony.com"),
            "v6" to CPIdentityGenerator.generateV6Identity("Android Fixture", "fixture@carrierpony.com")
        )
        for ((version, identity) in peers) {
            File(directory, "peer-android-$version-public.asc").writeText(identity.armoredPublicKey)
            File(directory, "peer-android-$version-secret.b64").writeText(encoder.encodeToString(identity.secretKey))
            File(directory, "peer-android-$version-fingerprint.txt").writeText(identity.fingerprint)
        }
        for ((from, to) in listOf("v4" to "v6", "v6" to "v6", "v6" to "v4")) {
            val name = "android-$from-to-ios-$to"
            val envelope = CPMessenger.signAndEncrypt(
                name.toByteArray(), peers.getValue(from).secretKey, null, ios(to).publicKey
            )
            File(directory, "$name.b64").writeText(encoder.encodeToString(envelope))
        }
        // Leftovers written for the previous peers can no longer be opened.
        directory.listFiles { file -> file.name.matches(Regex("ios-v[46]-to-android-v[46]\\.b64")) }
            ?.forEach { it.delete() }
    }

    // What was written for iOS opens here too, so a failure on the iOS side is
    // about the iOS reader and not about a bad fixture.
    @Test
    fun envelopesWrittenForIosOpenHere() {
        assumeTrue(exists("peer-android-v6-fingerprint.txt"))
        for ((from, to) in listOf("v4" to "v6", "v6" to "v6", "v6" to "v4")) {
            val name = "android-$from-to-ios-$to"
            val sender = android(from)
            val opened = CPMessenger.decryptAndVerify(base64("$name.b64"), ios(to).secretKey, null) { fpr ->
                if (fpr == sender.fingerprint) sender.publicKey else null
            }
            assertArrayEquals(name, name.toByteArray(), opened.plaintext)
            assertEquals(sender.fingerprint, opened.senderFingerprint)
        }
    }

    @Test
    fun envelopesWrittenByIosOpenHere() {
        assumeTrue(exists("ios-v6-to-android-v6.b64"))
        for ((from, to) in listOf("v6" to "v4", "v6" to "v6", "v4" to "v6")) {
            val name = "ios-$from-to-android-$to"
            val sender = ios(from)
            val opened = CPMessenger.decryptAndVerify(base64("$name.b64"), android(to).secretKey, null) { fpr ->
                if (fpr == sender.fingerprint) sender.publicKey else null
            }
            assertArrayEquals(name, name.toByteArray(), opened.plaintext)
            assertEquals(sender.fingerprint, opened.senderFingerprint)
        }
    }

    // The session-key packet iOS writes for a v6 recipient is the one this
    // core writes: version 6, the subkey's fingerprint, algorithm 35.
    @Test
    fun iosAddressesAV6RecipientByItsCompositeSubkey() {
        assumeTrue(exists("ios-v6-to-android-v6.b64"))
        val subkey = CPCompositeKeys.encryptionSubkey(CPMessenger.loadPublicRing(android("v6").publicKey))!!
        for (name in listOf("ios-v6-to-android-v6.b64", "ios-v4-to-android-v6.b64")) {
            val envelope = base64(name)
            val body = CPCompositeKeys.packetBody(envelope)
            val pkesk = CPCompositeMessage.parsePkesk(body)
            assertTrue("$name must start with an algorithm 35 session-key packet", pkesk != null)
            assertArrayEquals(subkey.fingerprint, pkesk!!.recipientFingerprint)
        }
    }
}
