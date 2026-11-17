// ContactRequestStoreTest.kt
// CarrierPony Android
//
// Receiving a contact request. Two things are pinned here. A request is only
// believed when its envelope was signed by the key it carries, checked with
// a key that is not a contact's. And the list of waiting requests stays
// bounded and remembers who was declined, because anyone who has a public key
// can send one.

package com.carrierpony.app

import com.carrierpony.app.crypto.CryptoException
import com.carrierpony.app.crypto.Fingerprint
import com.carrierpony.app.crypto.PonyCryptoEngine
import com.carrierpony.app.crypto.PublicKey
import com.carrierpony.app.messaging.ContactRequest
import com.carrierpony.app.messaging.ContactRequestStore
import com.carrierpony.core.CPGeneratedIdentity
import com.carrierpony.core.CPIdentityGenerator
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ContactRequestStoreTest {

    private lateinit var workDir: File

    @Before
    fun setUp() {
        workDir = Files.createTempDirectory("cp-request-test").toFile()
    }

    @After
    fun tearDown() {
        workDir.deleteRecursively()
    }

    private fun v4(name: String) = CPIdentityGenerator.generateV4Identity(name, "test@carrierpony.com")
    private fun v6(name: String) = CPIdentityGenerator.generateV6Identity(name, "test@carrierpony.com")
    private fun fpr(i: CPGeneratedIdentity) = Fingerprint.from(i.fingerprint)!!
    private fun key(i: CPGeneratedIdentity) = PublicKey(fpr(i), i.armoredPublicKey)

    /** An engine for [own] that knows nobody. */
    private fun engine(own: CPGeneratedIdentity) = PonyCryptoEngine(
        fingerprint = fpr(own),
        secretKey = own.secretKey,
        armored = own.armoredPublicKey,
        publicKeyResolver = { null }
    )

    // ── The signature check ────────────────────────────────────────────

    @Test
    fun anEnvelopeVerifiesUnderTheKeyItsSenderHandsOver() {
        for ((sender, receiver) in listOf(v6("S") to v6("R"), v4("S") to v6("R"), v6("S") to v4("R"), v4("S") to v4("R"))) {
            val envelope = engine(sender).signAndEncrypt("request".toByteArray(), key(receiver))
            // The receiver does not know the sender, so the ordinary open refuses.
            try {
                engine(receiver).decryptAndVerify(envelope)
                fail("an unknown sender must not verify")
            } catch (e: CryptoException) {
                // expected
            }
            val opened = engine(receiver).decryptAndVerify(envelope, key(sender))
            assertEquals(fpr(sender), opened.sender)
            assertArrayEquals("request".toByteArray(), opened.plaintext)
        }
    }

    @Test
    fun anEnvelopeSignedBySomeoneElseDoesNotVerifyUnderABorrowedKey() {
        val receiver = v6("Receiver")
        val impostor = v6("Impostor")
        for (victim in listOf(v6("Victim"), v4("Victim"))) {
            // The impostor signs, and would hand over the victim's public key.
            val envelope = engine(impostor).signAndEncrypt("request".toByteArray(), key(receiver))
            try {
                engine(receiver).decryptAndVerify(envelope, key(victim))
                fail("a borrowed key must not verify someone else's signature")
            } catch (e: CryptoException.NoValidSignature) {
                // expected
            }
        }
    }

    // ── The waiting list ───────────────────────────────────────────────

    private val a = Fingerprint.from("08108C383A81C409F3E28757397AC5A52B6C5A8D")!!
    private val b = Fingerprint.from("0F1E2D3C4B5A69788796A5B4C3D2E1F00112233445566778899AABBCCDDEEFF0")!!

    private fun request(fingerprint: Fingerprint, name: String? = "Sea Biscuit", caps: List<String>? = listOf("v6")) =
        ContactRequest(fingerprint, "KEY-${fingerprint.hex.takeLast(4)}", name, caps, 1_800_000_000)

    private fun store() = ContactRequestStore(workDir).apply { activate("test") }

    @Test
    fun requestsPersistAcrossInstances() {
        val first = store()
        assertTrue(first.add(request(a)))
        assertTrue(first.add(request(b, name = null, caps = null)))
        val again = store()
        assertEquals(listOf(a, b), again.requests.map { it.fingerprint })
        assertEquals("Sea Biscuit", again.requests[0].name)
        assertEquals(listOf("v6"), again.requests[0].caps)
        assertEquals("KEY-${a.hex.takeLast(4)}", again.requests[0].armoredPublicKey)
        assertEquals(1_800_000_000L, again.requests[0].receivedAt)
        assertNull(again.requests[1].name)
        assertNull(again.requests[1].caps)
    }

    @Test
    fun oneRequestPerPerson() {
        val store = store()
        assertTrue(store.add(request(a, name = "First")))
        assertTrue(store.add(request(a, name = "Second")))
        assertEquals(1, store.requests.size)
        assertEquals("Second", store.requests[0].name)
    }

    @Test
    fun aDeclinedPersonIsIgnoredFromThenOn() {
        val store = store()
        store.add(request(a))
        store.decline(a)
        assertTrue(store.requests.isEmpty())
        assertTrue(store.isDeclined(a))
        assertFalse(store.add(request(a)))
        assertTrue(store.requests.isEmpty())
        // It survives a restart, and it is per person.
        val again = store()
        assertFalse(again.add(request(a)))
        assertTrue(again.add(request(b)))
        // Adding their key yourself clears it.
        again.clearDeclined(a)
        assertFalse(again.isDeclined(a))
        assertTrue(again.add(request(a)))
    }

    @Test
    fun removingARequestRemembersNothing() {
        val store = store()
        store.add(request(a))
        store.remove(a)
        assertTrue(store.requests.isEmpty())
        assertFalse(store.isDeclined(a))
        assertTrue(store.add(request(a)))
    }

    @Test
    fun theListIsCapped() {
        val store = store()
        for (i in 0 until ContactRequestStore.MAX_REQUESTS) {
            val hex = "%040X".format(i + 1)
            assertTrue(store.add(request(Fingerprint.from(hex)!!)))
        }
        assertFalse(store.add(request(a)))
        assertEquals(ContactRequestStore.MAX_REQUESTS, store.requests.size)
        // Someone already on the list can still replace their own request.
        assertTrue(store.add(request(Fingerprint.from("%040X".format(1))!!, name = "Again")))
        assertEquals(ContactRequestStore.MAX_REQUESTS, store.requests.size)
    }

    @Test
    fun namesAreCleanedBeforeTheyAreShown() {
        assertEquals("Sea Biscuit", ContactRequestStore.cleanName("  Sea\u0000 Biscuit\n"))
        assertEquals(ContactRequestStore.MAX_NAME_LENGTH, ContactRequestStore.cleanName("x".repeat(500))!!.length)
        assertNull(ContactRequestStore.cleanName("   \n\t "))
        assertNull(ContactRequestStore.cleanName(null))
        val store = store()
        store.add(request(a, name = "A\nB" + "y".repeat(200)))
        assertEquals(ContactRequestStore.MAX_NAME_LENGTH, store.requests[0].name!!.length)
        assertFalse(store.requests[0].name!!.contains("\n"))
    }

    @Test
    fun accountsHaveSeparateLists() {
        val one = ContactRequestStore(workDir).apply { activate("one") }
        one.add(request(a))
        one.decline(b)
        val two = ContactRequestStore(workDir).apply { activate("two") }
        assertTrue(two.requests.isEmpty())
        assertFalse(two.isDeclined(b))
        // The same instance follows an account switch.
        one.activate("two")
        assertTrue(one.requests.isEmpty())
        one.activate("one")
        assertEquals(listOf(a), one.requests.map { it.fingerprint })
        assertTrue(one.isDeclined(b))
    }
}
