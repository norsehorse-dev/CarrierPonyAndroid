// StoreSupportTest.kt
// CarrierPony Android
//
// ContactStore persistence and mutation semantics, plus AttachmentStore file
// handling and filename sanitization.

package com.carrierpony.app

import com.carrierpony.app.attachments.AttachmentStore
import com.carrierpony.app.crypto.Fingerprint
import com.carrierpony.app.crypto.PublicKey
import com.carrierpony.app.messaging.Contact
import com.carrierpony.app.messaging.ContactStore
import com.carrierpony.app.messaging.TrustLevel
import com.carrierpony.core.CPIdentityGenerator
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class StoreSupportTest {

    private lateinit var workDir: File

    @Before
    fun setUp() {
        workDir = Files.createTempDirectory("cp-store-test").toFile()
        AttachmentStore.directory = File(workDir, "attachments")
    }

    @After
    fun tearDown() {
        workDir.deleteRecursively()
    }

    private fun someContact(name: String? = "Bob"): Contact {
        val fingerprint = Fingerprint.from("61D27B04E1E90899E8D162824891888765D64C51")!!
        return Contact(
            fingerprint = fingerprint,
            publicKey = PublicKey(fingerprint, "-----BEGIN PGP PUBLIC KEY BLOCK-----\n\nAA==\n-----END PGP PUBLIC KEY BLOCK-----\n"),
            name = name
        )
    }

    // ── ContactStore ───────────────────────────────────────────────────

    @Test
    fun contactsPersistAcrossInstances() {
        val store = ContactStore(workDir).apply { activate("test") }
        store.add(someContact())
        store.markVerified(someContact().fingerprint)
        store.setNickname(someContact().fingerprint, "Bobcat")

        val reloaded = ContactStore(workDir).apply { activate("test") }
        val contact = reloaded.contact(someContact().fingerprint)
        assertNotNull(contact)
        assertEquals("Bob", contact!!.name)
        assertEquals("Bobcat", contact.nickname)
        assertEquals("Bobcat", contact.displayName)
        assertEquals(TrustLevel.VERIFIED, contact.trust)
    }

    @Test
    fun addReplacesExistingFingerprint() {
        val store = ContactStore(workDir).apply { activate("test") }
        store.add(someContact(name = "Bob"))
        store.add(someContact(name = "Robert"))
        assertEquals(1, store.contacts.size)
        assertEquals("Robert", store.contacts.single().name)
    }

    @Test
    fun removeAndRemoveAll() {
        val store = ContactStore(workDir).apply { activate("test") }
        store.add(someContact())
        store.remove(someContact().fingerprint)
        assertTrue(store.contacts.isEmpty())
        store.add(someContact())
        store.removeAll()
        assertTrue(ContactStore(workDir).apply { activate("test") }.contacts.isEmpty())
    }

    @Test
    fun setNameTrimsAndClears() {
        val store = ContactStore(workDir).apply { activate("test") }
        store.add(someContact(name = null))
        store.setName(someContact().fingerprint, "  Spaced Out  ")
        assertEquals("Spaced Out", store.contact(someContact().fingerprint)!!.name)
        store.setName(someContact().fingerprint, "   ")
        assertNull(store.contact(someContact().fingerprint)!!.name)
    }

    @Test
    fun publicKeyDataDearmorsRealKey() {
        val identity = CPIdentityGenerator.generateV4Identity("Keyed", "keyed@carrierpony.com")
        val fingerprint = Fingerprint.from(identity.fingerprint)!!
        val store = ContactStore(workDir).apply { activate("test") }
        store.add(Contact(fingerprint, PublicKey(fingerprint, identity.armoredPublicKey)))
        val data = store.publicKeyData(fingerprint)
        assertNotNull(data)
        assertEquals(identity.fingerprint, com.carrierpony.core.CPKeyInfo.primaryFingerprint(data!!))
    }

    // ── AttachmentStore ────────────────────────────────────────────────

    @Test
    fun saveAndReadBack() {
        val bytes = byteArrayOf(9, 8, 7, 6)
        val localPath = AttachmentStore.save(bytes, "photo.png")
        assertTrue(localPath.endsWith("_photo.png"))
        assertArrayEquals(bytes, AttachmentStore.data(localPath))
        AttachmentStore.delete(localPath)
        assertNull(AttachmentStore.data(localPath))
    }

    @Test
    fun sanitizeStripsHostileNames() {
        assertEquals("....etcpasswd", AttachmentStore.sanitize("../../etc/passwd"))
        assertEquals("file", AttachmentStore.sanitize("///"))
        assertEquals("file", AttachmentStore.sanitize("   "))
        assertEquals(120, AttachmentStore.sanitize("x".repeat(500)).length)
    }

    @Test
    fun temporaryCopyCarriesBytes() {
        val localPath = AttachmentStore.save("copy me".toByteArray(), "note.txt")
        val copy = AttachmentStore.temporaryCopy(localPath, "note.txt")
        assertNotNull(copy)
        assertEquals("copy me", copy!!.readText())
        copy.delete()
    }
}
