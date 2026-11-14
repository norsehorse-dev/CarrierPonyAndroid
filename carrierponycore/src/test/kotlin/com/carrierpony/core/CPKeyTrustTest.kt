// CPKeyTrustTest.kt
// CarrierPonyCore
//
// A subkey is used only when the pinned primary key has signed for it. Every
// kind of key the apps produce has to pass (an iOS-made v4 identity, Android v4
// and v6 identities, the specification's sample key), and a ring with the right
// primary and somebody else's subkey has to be refused, for encrypting and for
// verifying.
//
// The files under /ios were written by the iOS key generator (the
// FixtureEmitterTests suite in the iOS project). The key belongs to nobody.

package com.carrierpony.core

import org.bouncycastle.bcpg.PublicKeyPacket
import org.bouncycastle.openpgp.PGPPublicKey
import org.bouncycastle.openpgp.PGPPublicKeyRing
import org.bouncycastle.openpgp.api.KeyPairGeneratorCallback
import org.bouncycastle.openpgp.api.OpenPGPKeyGenerator
import org.bouncycastle.openpgp.api.bc.BcOpenPGPImplementation
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.Date

class CPKeyTrustTest {

    private fun v6(name: String) = CPIdentityGenerator.generateV6Identity(name, "test@carrierpony.com")
    private fun v4(name: String) = CPIdentityGenerator.generateV4Identity(name, "test@carrierpony.com")

    private fun resource(path: String): ByteArray = javaClass.getResourceAsStream(path)!!.use { it.readBytes() }

    private fun ringOf(identity: CPGeneratedIdentity): PGPPublicKeyRing =
        CPMessenger.loadPublicRing(CPArmor.dearmor(identity.armoredPublicKey)!!)

    private fun subkeysOf(ring: PGPPublicKeyRing): List<PGPPublicKey> =
        ring.publicKeys.asSequence().filter { !it.isMasterKey }.toList()

    /** [victim]'s primary key with [attacker]'s subkeys, each still carrying the attacker's own binding. */
    private fun graft(victim: PGPPublicKeyRing, attacker: PGPPublicKeyRing): PGPPublicKeyRing =
        PGPPublicKeyRing(listOf(victim.publicKey) + subkeysOf(attacker))

    private fun encoded(ring: PGPPublicKeyRing): ByteArray = ring.encoded

    private fun assertEverySubkeyBound(ring: PGPPublicKeyRing) {
        val subkeys = subkeysOf(ring)
        assertTrue("expected at least one subkey", subkeys.isNotEmpty())
        for (subkey in subkeys) assertTrue("subkey algorithm ${subkey.algorithm} must be bound", CPKeyTrust.isBound(ring, subkey))
        assertTrue(CPKeyTrust.isBound(ring, ring.publicKey))
        assertTrue(CPKeyTrust.isBoundSigner(ring, ring.publicKey))
    }

    // ── Keys the apps produce are accepted ─────────────────────────────

    @Test
    fun iosMadeV4IdentityIsAccepted() {
        val ring = CPMessenger.loadPublicRing(resource("/ios/ios-v4-public.asc"))
        val fingerprint = String(resource("/ios/ios-v4-fingerprint.txt"), Charsets.UTF_8).trim()
        assertEquals(fingerprint, CPKeyInfo.bytesToHex(ring.publicKey.fingerprint))
        assertEverySubkeyBound(ring)
        assertNotNull(CPMessenger.findEncryptionKey(ring))
    }

    @Test
    fun androidV4IdentityIsAccepted() {
        val ring = ringOf(v4("A"))
        assertEverySubkeyBound(ring)
        assertNotNull(CPMessenger.findEncryptionKey(ring))
    }

    @Test
    fun androidV6IdentityIsAccepted() {
        val ring = ringOf(v6("A"))
        assertEverySubkeyBound(ring)
        assertTrue(CPKeyTrust.isBound(ring, CPCompositeKeys.encryptionSubkey(ring)!!))
    }

    @Test
    fun specificationSampleKeyIsAccepted() {
        val key = resource("/pqc/rfc9580-pqc-sample-key.asc")
        val ring = CPMessenger.loadPublicRing(CPArmor.dearmor(CPIdentityGenerator.exportArmoredPublicKey(key))!!)
        assertEverySubkeyBound(ring)
    }

    // ── Android and an iOS-made identity talk to each other ─────────────

    @Test
    fun androidEncryptsToAnIosMadeIdentityAndItDecrypts() {
        val android = v4("Android")
        val iosPublic = CPArmor.dearmor(String(resource("/ios/ios-v4-public.asc"), Charsets.UTF_8))!!
        val iosSecret = resource("/ios/ios-v4-secret.asc")
        val plaintext = "android to ios".toByteArray()
        val envelope = CPMessenger.signAndEncrypt(plaintext, android.secretKey, null, iosPublic)
        val opened = CPMessenger.decryptAndVerify(envelope, iosSecret, null) { fingerprint ->
            if (fingerprint == android.fingerprint) CPArmor.dearmor(android.armoredPublicKey) else null
        }
        assertArrayEquals(plaintext, opened.plaintext)
    }

    @Test
    fun anIosMadeIdentitySignsAndBothKindsOfAndroidIdentityVerify() {
        val iosPublic = CPArmor.dearmor(String(resource("/ios/ios-v4-public.asc"), Charsets.UTF_8))!!
        val iosSecret = resource("/ios/ios-v4-secret.asc")
        val iosFingerprint = String(resource("/ios/ios-v4-fingerprint.txt"), Charsets.UTF_8).trim()
        for (recipient in listOf(v4("Android v4"), v6("Android v6"))) {
            val plaintext = "ios to android".toByteArray()
            val envelope = CPMessenger.signAndEncrypt(plaintext, iosSecret, null, CPArmor.dearmor(recipient.armoredPublicKey)!!)
            val opened = CPMessenger.decryptAndVerify(envelope, recipient.secretKey, null) { fingerprint ->
                if (fingerprint == iosFingerprint) iosPublic else null
            }
            assertArrayEquals(plaintext, opened.plaintext)
            assertEquals(iosFingerprint, opened.senderFingerprint)
        }
    }

    // ── Somebody else's subkey under the right primary is refused ───────

    @Test
    fun graftedV4EncryptionSubkeyIsNotBound() {
        val grafted = graft(ringOf(v4("Victim")), ringOf(v4("Attacker")))
        assertEquals(CPKeyInfo.bytesToHex(grafted.publicKey.fingerprint).length, 40)
        assertFalse(CPKeyTrust.isBound(grafted, subkeysOf(grafted).single()))
        assertEquals(null, CPMessenger.findEncryptionKey(grafted))
    }

    @Test
    fun graftedV6EncryptionSubkeyIsNotBound() {
        val grafted = graft(ringOf(v6("Victim")), ringOf(v6("Attacker")))
        assertFalse(CPKeyTrust.isBound(grafted, CPCompositeKeys.encryptionSubkey(grafted)!!))
    }

    @Test
    fun encryptingToAGraftedSubkeyIsRefused() {
        val sender = v6("Sender")
        for ((victim, attacker) in listOf(v4("V") to v4("A"), v6("V") to v6("A"), v4("V") to v6("A"))) {
            val grafted = graft(ringOf(victim), ringOf(attacker))
            // The fingerprint an app would check still matches the victim.
            assertEquals(victim.fingerprint, CPKeyInfo.bytesToHex(grafted.publicKey.fingerprint))
            try {
                CPMessenger.signAndEncrypt("secret".toByteArray(), sender.secretKey, null, encoded(grafted))
                fail("encrypted to a subkey the recipient's primary key never signed")
            } catch (e: CPCryptoError.MalformedKey) {
                // expected
            }
        }
    }

    @Test
    fun aRingWithNoSubkeyHasNoEncryptionKey() {
        val ring = ringOf(v4("A"))
        val primaryOnly = PGPPublicKeyRing(listOf(ring.publicKey))
        assertEquals(null, CPMessenger.findEncryptionKey(primaryOnly))
    }

    // ── Signing subkeys need the back-signature ────────────────────────

    /** A v6 key with a real signing subkey, which generated identities do not have. */
    private fun ringWithSigningSubkey(): PGPPublicKeyRing {
        val generator = OpenPGPKeyGenerator(BcOpenPGPImplementation(), PublicKeyPacket.VERSION_6, false, Date())
        val key = generator
            .withPrimaryKey(KeyPairGeneratorCallback { it.generateEd25519KeyPair() })
            .addSigningSubkey(KeyPairGeneratorCallback { it.generateEd25519KeyPair() })
            .addUserId("Signer <signer@carrierpony.com>")
            .build()
        return PGPPublicKeyRing(key.getPGPSecretKeyRing().publicKeys.asSequence().toList())
    }

    @Test
    fun aProperlyBoundSigningSubkeyMaySign() {
        val ring = ringWithSigningSubkey()
        val subkey = subkeysOf(ring).single()
        assertTrue(CPKeyTrust.isBound(ring, subkey))
        assertTrue(CPKeyTrust.isBoundSigner(ring, subkey))
    }

    @Test
    fun aGraftedSigningSubkeyMayNotSign() {
        val grafted = graft(ringWithSigningSubkey(), ringWithSigningSubkey())
        val subkey = subkeysOf(grafted).single()
        assertFalse(CPKeyTrust.isBound(grafted, subkey))
        assertFalse(CPKeyTrust.isBoundSigner(grafted, subkey))
    }

    @Test
    fun anEncryptionSubkeyMayNotSign() {
        val ring = ringOf(v4("A"))
        // Bound, but it carries no back-signature, so it is not a signer.
        val subkey = subkeysOf(ring).single()
        assertTrue(CPKeyTrust.isBound(ring, subkey))
        assertFalse(CPKeyTrust.isBoundSigner(ring, subkey))
    }

    @Test
    fun aPrimaryFromAnotherRingIsNotBound() {
        val a = ringOf(v6("A"))
        val b = ringOf(v6("B"))
        assertFalse(CPKeyTrust.isBound(a, b.publicKey))
        assertFalse(CPKeyTrust.isBoundSigner(a, b.publicKey))
    }

    // ── Audit of real keys, off unless asked for ───────────────────────

    /** The shape of a ring, with no user ID or key material: what kind of key is this? */
    private fun describe(ring: PGPPublicKeyRing): List<String> {
        val primary = ring.publicKey
        val provider = org.bouncycastle.openpgp.operator.bc.BcPGPContentVerifierBuilderProvider()
        return ring.publicKeys.asSequence().map { key ->
            val types = key.signatures.asSequence()
                .mapNotNull { (it as? org.bouncycastle.openpgp.PGPSignature) }
                .map { "0x%02x/v%d/hash%d".format(it.signatureType, it.version, it.hashAlgorithm) }
                .toList()
            val verified = if (key.isMasterKey) "" else {
                val results = key.getSignaturesOfType(org.bouncycastle.openpgp.PGPSignature.SUBKEY_BINDING).asSequence().map { sig ->
                    try {
                        sig.init(provider, primary)
                        if (sig.verifyCertification(primary, key)) "valid" else "INVALID"
                    } catch (e: Exception) {
                        "error:" + e.javaClass.simpleName + ":" + (e.message ?: "")
                    }
                }.toList()
                " bindings=$results bound=${CPKeyTrust.isBound(ring, key)}"
            }
            val selfCerts = if (!key.isMasterKey) "" else {
                val results = key.signatures.asSequence()
                    .mapNotNull { it as? org.bouncycastle.openpgp.PGPSignature }
                    .filter { it.signatureType in 0x10..0x13 }
                    .map { sig ->
                        try {
                            sig.init(provider, primary)
                            val userID = key.userIDs.asSequence().firstOrNull()
                            if (userID != null && sig.verifyCertification(userID, primary)) "valid" else "INVALID"
                        } catch (e: Exception) {
                            "error:" + e.javaClass.simpleName
                        }
                    }.toList()
                " userIdCerts=$results"
            }
            val mpis = key.signatures.asSequence()
                .mapNotNull { it as? org.bouncycastle.openpgp.PGPSignature }
                .map { signatureMpiBits(it) }
                .toList()
            (if (key.isMasterKey) "primary" else "subkey") +
                " v${key.version} algorithm=${key.algorithm} bits=${key.bitStrength}" +
                " canEncrypt=${key.isEncryptionKey} created=${key.creationTime.time / 1000}" +
                " keyPacketBytes=${key.publicKeyPacket.encodedContents.size}" +
                " signatures=$types sigMpiBits=$mpis$selfCerts$verified"
        }.toList()
    }

    /** The declared bit lengths of a v4 signature's MPIs (r and s for EdDSA), read from the packet bytes. */
    private fun signatureMpiBits(signature: org.bouncycastle.openpgp.PGPSignature): String = try {
        val body = CPCompositeKeys.packetBody(signature.encoded)
        var i = 4
        val hashedLength = ((body[i].toInt() and 0xFF) shl 8) or (body[i + 1].toInt() and 0xFF)
        i += 2 + hashedLength
        val unhashedLength = ((body[i].toInt() and 0xFF) shl 8) or (body[i + 1].toInt() and 0xFF)
        i += 2 + unhashedLength + 2
        val bits = mutableListOf<String>()
        while (i + 2 <= body.size) {
            val n = ((body[i].toInt() and 0xFF) shl 8) or (body[i + 1].toInt() and 0xFF)
            val bytes = (n + 7) / 8
            val firstByte = if (i + 2 < body.size) body[i + 2].toInt() and 0xFF else -1
            bits.add("$n(first=0x%02x)".format(firstByte))
            i += 2 + bytes
        }
        "$bits trailing=${body.size - i} hashed=$hashedLength unhashed=$unhashedLength"
    } catch (e: Exception) {
        "unreadable"
    }

    // Generates many v4 identities and checks every one. A binding that fails for
    // one key in hundreds points at an encoding edge case (a value with leading
    // zero bits), and only volume finds those. CP_STRESS sets the count.
    @Test
    fun everyGeneratedV4IdentityHasAValidBinding() {
        val count = System.getenv("CP_STRESS")?.toIntOrNull() ?: 200
        var failures = 0
        repeat(count) {
            val identity = v4("Stress")
            val ring = ringOf(identity)
            if (CPMessenger.findEncryptionKey(ring) == null) {
                failures++
                println("[stress] refused ${identity.fingerprint}")
                describe(ring).forEach { println("[stress]   $it") }
            }
        }
        println("[stress] android v4 generated=$count refused=$failures")
        assertEquals(0, failures)
    }

    // CP_AUDIT_KEYS=/path/to/file ./gradlew :carrierponycore:test --tests '*CPKeyTrustTest*'
    // The file holds one base64-encoded armored public key per line. Nothing is
    // written anywhere; the counts and any failing fingerprints go to the
    // console. It answers one question before the check ships: would it refuse
    // the encryption subkey of any identity that exists today?
    @Test
    fun auditPublicKeysFromAFile() {
        val path = System.getenv("CP_AUDIT_KEYS")
        assumeTrue("Set CP_AUDIT_KEYS to a file of base64 public keys to run the audit", !path.isNullOrEmpty())
        var total = 0
        var accepted = 0
        var unparseable = 0
        val refused = mutableListOf<String>()
        File(path!!).forEachLine { raw ->
            val line = raw.trim()
            if (line.isEmpty()) return@forEachLine
            total++
            val ring = try {
                CPMessenger.loadPublicRing(java.util.Base64.getDecoder().decode(line))
            } catch (e: Exception) {
                unparseable++
                return@forEachLine
            }
            val usable = CPMessenger.findEncryptionKey(ring) != null ||
                CPCompositeKeys.encryptionSubkey(ring)?.let { CPKeyTrust.isBound(ring, it) } == true
            if (usable) {
                accepted++
            } else {
                refused.add(CPKeyInfo.bytesToHex(ring.publicKey.fingerprint))
                describe(ring).forEach { println("[audit]   $it") }
            }
        }
        println("[audit] keys=$total accepted=$accepted refused=${refused.size} unparseable=$unparseable")
        refused.forEach { println("[audit] refused $it") }
        assertEquals("identities whose encryption subkey would be refused", 0, refused.size)
        assertEquals("keys that did not parse", 0, unparseable)
    }
}
