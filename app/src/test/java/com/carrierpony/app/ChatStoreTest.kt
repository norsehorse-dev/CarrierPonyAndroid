// ChatStoreTest.kt
// CarrierPony Android
//
// End-to-end through a real local relay: the stub queues envelopes per
// recipient fingerprint with per-device ack tracking, exactly like
// api.carrierpony.com's delivery model. Alice and Bob run real ChatStores
// over real crypto — so these tests exercise send, local echo, self-copy
// multi-device sync, read-receipt controls, profile controls, dedup, expiry,
// stranger refusal, persistence, and purge with nothing faked below the HTTP
// line.

package com.carrierpony.app

import com.carrierpony.app.attachments.AttachmentStore
import com.carrierpony.app.crypto.Fingerprint
import com.carrierpony.app.crypto.PonyCryptoEngine
import com.carrierpony.app.crypto.PublicKey
import com.carrierpony.app.envelope.Threading
import com.carrierpony.app.messaging.ChatStore
import com.carrierpony.app.messaging.Contact
import com.carrierpony.app.messaging.EnvelopeFactory
import com.carrierpony.app.messaging.MessageDirection
import com.carrierpony.app.messaging.OutgoingMessage
import com.carrierpony.app.relay.RelayClient
import com.carrierpony.core.CPGeneratedIdentity
import com.carrierpony.core.CPIdentityGenerator
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.AfterClass
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.encoding.Base64

class ChatStoreTest {

    // ── In-memory relay ────────────────────────────────────────────────

    private class QueuedMessage(val id: String, val envelopeB64: String, val expiresAt: Long) {
        val ackedBy = mutableSetOf<String>()
    }

    companion object {
        private lateinit var server: HttpServer
        private lateinit var alice: CPGeneratedIdentity
        private lateinit var bob: CPGeneratedIdentity
        private lateinit var charlie: CPGeneratedIdentity

        private val queues = mutableMapOf<String, MutableList<QueuedMessage>>()
        private val counter = AtomicInteger(0)

        private fun enqueue(toFpr: String, envelopeB64: String, expiresAt: Long): String {
            val id = "m${counter.incrementAndGet()}"
            synchronized(queues) {
                queues.getOrPut(toFpr) { mutableListOf() }.add(QueuedMessage(id, envelopeB64, expiresAt))
            }
            return id
        }

        @BeforeClass
        @JvmStatic
        fun startRelay() {
            alice = CPIdentityGenerator.generateV4Identity("Alice", "alice@carrierpony.com")
            bob = CPIdentityGenerator.generateV4Identity("Bob", "bob@carrierpony.com")
            charlie = CPIdentityGenerator.generateV4Identity("Charlie", "charlie@carrierpony.com")

            server = HttpServer.create(InetSocketAddress(0), 0)
            server.createContext("/v1/challenge") { ex ->
                body(ex); reply(ex, JSONObject().put("nonce", "00112233445566778899aabbccddeeff"))
            }
            server.createContext("/v1/register-device") { ex ->
                body(ex); reply(ex, JSONObject().put("ok", true))
            }
            server.createContext("/v1/send") { ex ->
                val b = body(ex)
                val id = enqueue(b.getString("to_fpr"), b.getString("envelope"), b.getLong("expires_at"))
                reply(ex, JSONObject().put("message_id", id).put("expires_at", b.getLong("expires_at")))
            }
            server.createContext("/v1/inbox") { ex ->
                val b = body(ex)
                val device = b.getString("device_id")
                val messages = JSONArray()
                synchronized(queues) {
                    for (message in queues[b.getString("fpr")] ?: emptyList()) {
                        if (device in message.ackedBy) continue
                        messages.put(JSONObject()
                            .put("message_id", message.id)
                            .put("envelope", message.envelopeB64)
                            .put("received_at", "2026-07-08 12:00:00")
                            .put("expires_at", "2026-08-08 12:00:00"))
                    }
                }
                reply(ex, JSONObject().put("messages", messages))
            }
            server.createContext("/v1/ack") { ex ->
                val b = body(ex)
                val device = b.getString("device_id")
                val ids = b.getJSONArray("message_ids")
                var acked = 0
                synchronized(queues) {
                    val wanted = (0 until ids.length()).map { ids.getString(it) }.toSet()
                    for (message in queues[b.getString("fpr")] ?: emptyList()) {
                        if (message.id in wanted && message.ackedBy.add(device)) acked++
                    }
                }
                reply(ex, JSONObject().put("acked", acked))
            }
            server.createContext("/v1/report") { ex ->
                body(ex); reply(ex, JSONObject().put("ok", true))
            }
            server.start()
        }

        @AfterClass
        @JvmStatic
        fun stopRelay() {
            server.stop(0)
        }

        private fun body(ex: HttpExchange): JSONObject =
            JSONObject(String(ex.requestBody.readBytes(), Charsets.UTF_8))

        private fun reply(ex: HttpExchange, json: JSONObject) {
            val bytes = json.toString().toByteArray(Charsets.UTF_8)
            ex.sendResponseHeaders(200, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
    }

    // ── Per-test wiring ────────────────────────────────────────────────

    private lateinit var workDir: File
    private lateinit var scope: CoroutineScope

    private fun publicKey(of: CPGeneratedIdentity) =
        PublicKey(Fingerprint.from(of.fingerprint)!!, of.armoredPublicKey)

    private fun contact(of: CPGeneratedIdentity, name: String? = null) =
        Contact(fingerprint = Fingerprint.from(of.fingerprint)!!, publicKey = publicKey(of), name = name)

    private fun engine(own: CPGeneratedIdentity, knows: List<CPGeneratedIdentity>) = PonyCryptoEngine(
        fingerprint = Fingerprint.from(own.fingerprint)!!,
        secretKey = own.secretKey,
        armored = own.armoredPublicKey,
        publicKeyResolver = { fpr ->
            // Own key for self-copies, else the known peers.
            (listOf(own) + knows).firstOrNull { it.fingerprint == fpr.hex }?.let { publicKey(it) }
        }
    )

    private fun store(
        own: CPGeneratedIdentity,
        knows: List<CPGeneratedIdentity>,
        deviceID: String,
        multiDevice: Boolean = true,
        contactNames: MutableMap<String, String?> = mutableMapOf(),
        dir: File = workDir
    ): ChatStore {
        val crypto = engine(own, knows)
        val relay = RelayClient("http://127.0.0.1:${server.address.port}", crypto, deviceID)
        return ChatStore(
            identity = Fingerprint.from(own.fingerprint)!!,
            relay = relay,
            crypto = crypto,
            contacts = { knows.map { contact(it) } },
            multiDevice = multiDevice,
            updatePeerName = { fpr, name -> contactNames[fpr.hex] = name },
            storageDir = dir,
            scope = scope
        )
    }

    @Before
    fun setUp() {
        workDir = Files.createTempDirectory("cp-chat-test").toFile()
        AttachmentStore.directory = File(workDir, "attachments")
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        synchronized(queues) { queues.clear() }
    }

    @After
    fun tearDown() {
        scope.cancel()
        workDir.deleteRecursively()
    }

    // ── Tests ──────────────────────────────────────────────────────────

    @Test
    fun textMessageDeliversEndToEnd() = runBlocking {
        val aliceStore = store(alice, knows = listOf(bob), deviceID = "a".repeat(32))
        val bobStore = store(bob, knows = listOf(alice), deviceID = "b".repeat(32))

        aliceStore.send(text = "Neigh means neigh", to = contact(bob, "Bob"))

        // Local echo on Alice, outgoing and read.
        val threadID = Threading.pairwise(alice.fingerprint, bob.fingerprint)
        val echo = aliceStore.conversations.value[threadID]!!.messages.single()
        assertEquals(MessageDirection.OUTGOING, echo.direction)
        assertTrue(echo.isRead)

        // Delivered to Bob, incoming and unread.
        bobStore.refresh()
        val conversation = bobStore.conversations.value[threadID]
        assertNotNull(conversation)
        val message = conversation!!.messages.single()
        assertEquals("Neigh means neigh", message.text)
        assertEquals(MessageDirection.INCOMING, message.direction)
        assertEquals(1, conversation.unreadCount)
        assertEquals(alice.fingerprint, message.peer.hex)
        assertNull(bobStore.lastError.value)
    }

    @Test
    fun attachmentLandsOnDisk() = runBlocking {
        val aliceStore = store(alice, knows = listOf(bob), deviceID = "a".repeat(32))
        val bobStore = store(bob, knows = listOf(alice), deviceID = "b".repeat(32))
        val blob = ByteArray(70_000) { (it % 251).toByte() }

        aliceStore.send(
            text = null,
            attachments = listOf(OutgoingMessage.Attachment("photo.bin", "application/octet-stream", blob)),
            to = contact(bob)
        )
        bobStore.refresh()

        val threadID = Threading.pairwise(alice.fingerprint, bob.fingerprint)
        val attachment = bobStore.conversations.value[threadID]!!.messages.single().attachments.single()
        assertEquals("photo.bin", attachment.filename)
        assertEquals(blob.size.toLong(), attachment.size)
        assertArrayEquals(blob, AttachmentStore.data(attachment.localPath))
    }

    @Test
    fun selfCopySyncsToSecondDevice() = runBlocking {
        val deviceA = store(alice, knows = listOf(bob), deviceID = "a".repeat(32))
        val deviceB = store(alice, knows = listOf(bob), deviceID = "c".repeat(32))

        deviceA.send(text = "from device A", to = contact(bob, "Bob"))
        deviceB.refresh()

        val threadID = Threading.pairwise(alice.fingerprint, bob.fingerprint)
        val synced = deviceB.conversations.value[threadID]!!.messages.single()
        assertEquals("from device A", synced.text)
        assertEquals(MessageDirection.OUTGOING, synced.direction)
        assertEquals(bob.fingerprint, synced.peer.hex)
    }

    @Test
    fun readReceiptSyncsAcrossDevices() = runBlocking {
        val bobDeviceA = store(bob, knows = listOf(alice), deviceID = "b".repeat(32))
        val bobDeviceB = store(bob, knows = listOf(alice), deviceID = "d".repeat(32))
        val aliceStore = store(alice, knows = listOf(bob), deviceID = "a".repeat(32))

        aliceStore.send(text = "read me", to = contact(bob))
        val threadID = Threading.pairwise(alice.fingerprint, bob.fingerprint)

        bobDeviceA.refresh()
        bobDeviceB.refresh()
        assertEquals(1, bobDeviceB.conversations.value[threadID]!!.unreadCount)

        bobDeviceA.markRead(threadID)      // emits a "read" control to self
        bobDeviceB.refresh()
        assertEquals(0, bobDeviceB.conversations.value[threadID]!!.unreadCount)
    }

    @Test
    fun profileControlRenamesPeer() = runBlocking {
        val names = mutableMapOf<String, String?>()
        val aliceStore = store(alice, knows = listOf(bob), deviceID = "a".repeat(32))
        val bobStore = store(bob, knows = listOf(alice), deviceID = "b".repeat(32), contactNames = names)

        // Seed a thread so the rename also lands on the conversation.
        aliceStore.send(text = "hello", to = contact(bob))
        bobStore.refresh()

        aliceStore.sendProfile(name = "Alice Ponysworth", to = contact(bob))
        bobStore.refresh()

        assertEquals("Alice Ponysworth", names[alice.fingerprint])
        val threadID = Threading.pairwise(alice.fingerprint, bob.fingerprint)
        assertEquals("Alice Ponysworth", bobStore.conversations.value[threadID]!!.peerName)
    }

    @Test
    fun strangerMessageIsRefused() = runBlocking {
        // Charlie is not among Bob's contacts, so his signature cannot resolve.
        val charlieStore = store(charlie, knows = listOf(bob), deviceID = "e".repeat(32))
        val bobStore = store(bob, knows = listOf(alice), deviceID = "b".repeat(32))

        charlieStore.send(text = "let me in", to = contact(bob))
        bobStore.refresh()

        assertTrue(bobStore.conversations.value.isEmpty())
        assertEquals(
            "Couldn't open an incoming message. Is the sender paired on this device?",
            bobStore.lastError.value
        )
    }

    @Test
    fun duplicateEnvelopeFilesOnce() = runBlocking {
        val bobStore = store(bob, knows = listOf(alice), deviceID = "b".repeat(32))

        // Build one envelope and enqueue it twice under different relay ids,
        // as a relay redelivery would.
        val factory = EnvelopeFactory(engine(alice, listOf(bob)))
        val threadID = Threading.pairwise(alice.fingerprint, bob.fingerprint)
        val envelope = factory.build(
            OutgoingMessage(threadID = threadID, text = "once only", expiresAt = System.currentTimeMillis() / 1000 + 3600),
            to = publicKey(bob)
        )
        val b64 = Base64.Default.encode(envelope)
        enqueue(bob.fingerprint, b64, 0)
        enqueue(bob.fingerprint, b64, 0)

        bobStore.refresh()
        assertEquals(1, bobStore.conversations.value[threadID]!!.messages.size)
    }

    @Test
    fun expiredEnvelopeIsDropped() = runBlocking {
        val bobStore = store(bob, knows = listOf(alice), deviceID = "b".repeat(32))
        val factory = EnvelopeFactory(engine(alice, listOf(bob)))
        val threadID = Threading.pairwise(alice.fingerprint, bob.fingerprint)
        val envelope = factory.build(
            OutgoingMessage(threadID = threadID, text = "too late", expiresAt = System.currentTimeMillis() / 1000 - 10),
            to = publicKey(bob)
        )
        enqueue(bob.fingerprint, Base64.Default.encode(envelope), 0)

        bobStore.refresh()
        assertTrue(bobStore.conversations.value.isEmpty())
        assertNull(bobStore.lastError.value)
    }

    @Test
    fun conversationsSurviveRestart() = runBlocking {
        val bobStore = store(bob, knows = listOf(alice), deviceID = "b".repeat(32))
        val aliceStore = store(alice, knows = listOf(bob), deviceID = "a".repeat(32))
        aliceStore.send(text = "persist me", to = contact(bob))
        bobStore.refresh()

        // A fresh store over the same directory restores the thread and dedup set.
        val reborn = store(bob, knows = listOf(alice), deviceID = "b".repeat(32))
        val threadID = Threading.pairwise(alice.fingerprint, bob.fingerprint)
        assertEquals("persist me", reborn.conversations.value[threadID]!!.messages.single().text)
    }

    @Test
    fun purgeWipesDiskAndAttachments() = runBlocking {
        val aliceStore = store(alice, knows = listOf(bob), deviceID = "a".repeat(32))
        val bobStore = store(bob, knows = listOf(alice), deviceID = "b".repeat(32))
        aliceStore.send(
            text = "with file",
            attachments = listOf(OutgoingMessage.Attachment("f.bin", "application/octet-stream", byteArrayOf(1, 2, 3))),
            to = contact(bob)
        )
        bobStore.refresh()
        val threadID = Threading.pairwise(alice.fingerprint, bob.fingerprint)
        val localPath = bobStore.conversations.value[threadID]!!.messages.single().attachments.single().localPath
        assertNotNull(AttachmentStore.data(localPath))

        bobStore.purge()
        assertTrue(bobStore.conversations.value.isEmpty())
        assertNull(AttachmentStore.data(localPath))
        assertTrue(store(bob, knows = listOf(alice), deviceID = "b".repeat(32)).conversations.value.isEmpty())
    }

    @Test
    fun demoContactAutoReplies() = runBlocking {
        val aliceStore = store(alice, knows = emptyList(), deviceID = "a".repeat(32))
        val demoContact = Contact(
            fingerprint = DemoMode.fingerprint,
            publicKey = PublicKey(DemoMode.fingerprint, ""),
            name = DemoMode.peerName
        )
        aliceStore.send(text = "hello demo", to = demoContact)

        val threadID = Threading.pairwise(alice.fingerprint, DemoMode.fingerprintHex)
        val messages = aliceStore.conversations.value[threadID]!!.messages
        assertEquals(2, messages.size)
        val reply = messages.single { it.direction == MessageDirection.INCOMING }
        assertTrue(reply.text!!.startsWith("Received and decrypted on-device."))
        assertEquals(1, messages.count { it.direction == MessageDirection.OUTGOING })
    }
}
