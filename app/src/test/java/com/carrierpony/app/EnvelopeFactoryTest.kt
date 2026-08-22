// EnvelopeFactoryTest.kt
// CarrierPony Android
//
// End-to-end over the real core: two generated identities, two
// PonyCryptoEngines, an EnvelopeFactory each — build on one side, open on the
// other. This is the full app-side path a message takes minus the relay, and
// it exercises CPN1 + Manifest + CryptoEngine together exactly as ChatStore
// will in the next phases.

package com.carrierpony.app

import com.carrierpony.app.crypto.CryptoException
import com.carrierpony.app.crypto.Fingerprint
import com.carrierpony.app.crypto.PonyCryptoEngine
import com.carrierpony.app.crypto.PublicKey
import com.carrierpony.app.envelope.Threading
import com.carrierpony.app.messaging.EnvelopeFactory
import com.carrierpony.app.messaging.OutgoingMessage
import com.carrierpony.core.CPGeneratedIdentity
import com.carrierpony.core.CPIdentityGenerator
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.BeforeClass
import org.junit.Test

class EnvelopeFactoryTest {

    companion object {
        private lateinit var alice: CPGeneratedIdentity
        private lateinit var bob: CPGeneratedIdentity
        private lateinit var alicePub: PublicKey
        private lateinit var bobPub: PublicKey

        @BeforeClass
        @JvmStatic
        fun setUp() {
            alice = CPIdentityGenerator.generateV4Identity("Alice", "alice@carrierpony.com")
            bob = CPIdentityGenerator.generateV4Identity("Bob", "bob@carrierpony.com")
            alicePub = PublicKey(Fingerprint.from(alice.fingerprint)!!, alice.armoredPublicKey)
            bobPub = PublicKey(Fingerprint.from(bob.fingerprint)!!, bob.armoredPublicKey)
        }

        private fun engine(own: CPGeneratedIdentity, knows: List<PublicKey>): PonyCryptoEngine =
            PonyCryptoEngine(
                fingerprint = Fingerprint.from(own.fingerprint)!!,
                secretKey = own.secretKey,
                armored = own.armoredPublicKey,
                publicKeyResolver = { fpr -> knows.firstOrNull { it.fingerprint == fpr } }
            )
    }

    @Test
    fun textMessageRoundTrips() {
        val threadID = Threading.pairwise(alice.fingerprint, bob.fingerprint)
        val aliceFactory = EnvelopeFactory(engine(alice, listOf(bobPub)))
        val bobFactory = EnvelopeFactory(engine(bob, listOf(alicePub)))

        val expires = System.currentTimeMillis() / 1000 + 86_400
        val envelope = aliceFactory.build(
            OutgoingMessage(threadID = threadID, text = "Neigh means neigh", expiresAt = expires),
            to = bobPub
        )

        val incoming = bobFactory.open(envelope)
        assertEquals(alice.fingerprint, incoming.sender.hex)
        assertEquals("Neigh means neigh", incoming.text)
        assertEquals(threadID, incoming.manifest.threadID)
        assertEquals(bob.fingerprint, incoming.manifest.to)
        assertEquals(expires, incoming.manifest.expiresAt)
        assertEquals("message", incoming.manifest.type)
        assertTrue(incoming.files.isEmpty())
    }

    @Test
    fun attachmentsRoundTrip() {
        val threadID = Threading.pairwise(alice.fingerprint, bob.fingerprint)
        val aliceFactory = EnvelopeFactory(engine(alice, listOf(bobPub)))
        val bobFactory = EnvelopeFactory(engine(bob, listOf(alicePub)))

        val blob = ByteArray(65_537) { (it % 253).toByte() }
        val envelope = aliceFactory.build(
            OutgoingMessage(
                threadID = threadID,
                text = "file attached",
                attachments = listOf(OutgoingMessage.Attachment("blob.bin", "application/octet-stream", blob)),
                expiresAt = 0
            ),
            to = bobPub
        )

        val incoming = bobFactory.open(envelope)
        assertEquals("file attached", incoming.text)
        assertEquals(1, incoming.files.size)
        val (part, data) = incoming.files[0]
        assertEquals("blob.bin", part.filename)
        assertEquals("application/octet-stream", part.mime)
        assertEquals(blob.size.toLong(), part.size)
        assertArrayEquals(blob, data)
    }

    @Test
    fun fileOnlyMessageHasNullText() {
        val threadID = Threading.pairwise(alice.fingerprint, bob.fingerprint)
        val aliceFactory = EnvelopeFactory(engine(alice, listOf(bobPub)))
        val bobFactory = EnvelopeFactory(engine(bob, listOf(alicePub)))

        val envelope = aliceFactory.build(
            OutgoingMessage(
                threadID = threadID,
                attachments = listOf(OutgoingMessage.Attachment("a.txt", "text/plain", "abc".toByteArray())),
                expiresAt = 0
            ),
            to = bobPub
        )
        val incoming = bobFactory.open(envelope)
        assertNull(incoming.text)
        assertEquals(1, incoming.files.size)
    }

    @Test
    fun strangerEnvelopeIsRefused() {
        val threadID = Threading.pairwise(alice.fingerprint, bob.fingerprint)
        val aliceFactory = EnvelopeFactory(engine(alice, listOf(bobPub)))
        // Bob's engine knows nobody: Alice's claimed fingerprint resolves to null.
        val bobFactory = EnvelopeFactory(engine(bob, emptyList()))

        val envelope = aliceFactory.build(
            OutgoingMessage(threadID = threadID, text = "hello?", expiresAt = 0),
            to = bobPub
        )
        try {
            bobFactory.open(envelope)
            fail("Expected NoValidSignature for a sender who is not a contact")
        } catch (e: CryptoException.NoValidSignature) {
            // Correct: unknown senders never yield plaintext.
        }
    }

    @Test
    fun messageIDIsCarriedThrough() {
        val threadID = Threading.pairwise(alice.fingerprint, bob.fingerprint)
        val aliceFactory = EnvelopeFactory(engine(alice, listOf(bobPub)))
        val bobFactory = EnvelopeFactory(engine(bob, listOf(alicePub)))

        val envelope = aliceFactory.build(
            OutgoingMessage(threadID = threadID, text = "id check", expiresAt = 0),
            to = bobPub,
            messageID = "F00DFACE-0000-4000-8000-00000000BEEF"
        )
        assertEquals("F00DFACE-0000-4000-8000-00000000BEEF", bobFactory.open(envelope).manifest.messageID)
    }
}
