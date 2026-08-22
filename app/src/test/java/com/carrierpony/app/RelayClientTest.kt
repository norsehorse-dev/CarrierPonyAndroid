// RelayClientTest.kt
// CarrierPony Android
//
// The client tested against a real local HTTP server (JDK built-in) that
// plays relay: it issues nonces from /v1/challenge and — like the production
// relay does with GnuPG — cryptographically verifies the client's detached
// signature over the nonce before answering authenticated endpoints. So these
// tests prove the full challenge-auth loop, not just request shapes.

package com.carrierpony.app

import com.carrierpony.app.crypto.Fingerprint
import com.carrierpony.app.crypto.PonyCryptoEngine
import com.carrierpony.app.relay.RelayClient
import com.carrierpony.app.relay.RelayException
import com.carrierpony.core.CPArmor
import com.carrierpony.core.CPGeneratedIdentity
import com.carrierpony.core.CPIdentityGenerator
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.bouncycastle.openpgp.PGPPublicKeyRing
import org.bouncycastle.openpgp.PGPSignatureList
import org.bouncycastle.openpgp.PGPUtil
import org.bouncycastle.openpgp.bc.BcPGPObjectFactory
import org.bouncycastle.openpgp.operator.bc.BcKeyFingerprintCalculator
import org.bouncycastle.openpgp.operator.bc.BcPGPContentVerifierBuilderProvider
import org.json.JSONArray
import org.json.JSONObject
import org.junit.AfterClass
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.BeforeClass
import org.junit.Test
import java.io.ByteArrayInputStream
import java.net.InetSocketAddress
import kotlin.io.encoding.Base64

class RelayClientTest {

    companion object {
        private lateinit var server: HttpServer
        private lateinit var identity: CPGeneratedIdentity
        private lateinit var client: RelayClient

        private const val NONCE = "6f1d2c3b4a5968778695a4b3c2d1e0f1"
        private const val DEVICE_ID = "test-device-42"

        /** Requests seen by the stub, keyed by path (last body wins). */
        private val seen = mutableMapOf<String, JSONObject>()

        @BeforeClass
        @JvmStatic
        fun startStubRelay() {
            identity = CPIdentityGenerator.generateV4Identity("Relay Tester", "relay@carrierpony.com")
            server = HttpServer.create(InetSocketAddress(0), 0)

            server.createContext("/v1/challenge") { exchange ->
                readBody(exchange)
                respond(exchange, 200, JSONObject().put("nonce", NONCE))
            }
            server.createContext("/v1/register-device") { exchange ->
                val body = readBody(exchange)
                respondAuthed(exchange, body, JSONObject().put("ok", true), pubkeyFromBody = true)
            }
            server.createContext("/v1/send") { exchange ->
                val body = readBody(exchange)
                respondAuthed(exchange, body, JSONObject()
                    .put("message_id", "m-123")
                    .put("expires_at", 1_999_999_999L))
            }
            server.createContext("/v1/inbox") { exchange ->
                val body = readBody(exchange)
                val messages = JSONArray()
                    .put(JSONObject()
                        .put("message_id", "a1")
                        .put("envelope", Base64.Default.encode("first".toByteArray()))
                        .put("received_at", "2026-07-08 12:00:00")
                        .put("expires_at", "2026-07-15 12:00:00"))
                    .put(JSONObject()
                        .put("message_id", "a2")
                        .put("envelope", Base64.Default.encode("second".toByteArray()))
                        .put("received_at", "2026-07-08 12:00:05")
                        .put("expires_at", "2026-07-15 12:00:05"))
                respondAuthed(exchange, body, JSONObject().put("messages", messages))
            }
            server.createContext("/v1/ack") { exchange ->
                val body = readBody(exchange)
                respondAuthed(exchange, body,
                    JSONObject().put("acked", body.getJSONArray("message_ids").length()))
            }
            server.createContext("/v1/register-push") { exchange ->
                val body = readBody(exchange)
                respondAuthed(exchange, body, JSONObject().put("ok", true))
            }
            server.createContext("/v1/pair/offer") { exchange ->
                val body = readBody(exchange)
                respondAuthed(exchange, body, JSONObject()
                    .put("token", "0123456789abcdef0123456789abcdef")
                    .put("expires_in", 600))
            }
            server.createContext("/v1/pair/accept") { exchange ->
                val body = readBody(exchange)
                respondAuthed(exchange, body, JSONObject()
                    .put("offerer_fpr", identity.fingerprint)
                    .put("offerer_pubkey", identity.armoredPublicKey))
            }
            server.createContext("/v1/pair/status") { exchange ->
                val body = readBody(exchange)
                respondAuthed(exchange, body, JSONObject().put("state", "pending"))
            }
            server.createContext("/v1/report") { exchange ->
                val body = readBody(exchange)
                respondAuthed(exchange, body, JSONObject().put("ok", true))
            }
            server.createContext("/v1/forbidden") { exchange ->
                readBody(exchange)
                respond(exchange, 403, JSONObject().put("error", "unknown_fingerprint"))
            }
            server.start()

            client = RelayClient(
                baseURL = "http://127.0.0.1:${server.address.port}///",   // trailing slashes on purpose
                crypto = PonyCryptoEngine(
                    fingerprint = Fingerprint.from(identity.fingerprint)!!,
                    secretKey = identity.secretKey,
                    armored = identity.armoredPublicKey,
                    publicKeyResolver = { null }
                ),
                deviceID = DEVICE_ID
            )
        }

        @AfterClass
        @JvmStatic
        fun stopStubRelay() {
            server.stop(0)
        }

        // ── Stub internals ─────────────────────────────────────────────

        private fun readBody(exchange: HttpExchange): JSONObject {
            val body = JSONObject(String(exchange.requestBody.readBytes(), Charsets.UTF_8))
            seen[exchange.requestURI.path] = body
            return body
        }

        /** Verify fpr + nonce + sig like the production relay does, then answer. */
        private fun respondAuthed(
            exchange: HttpExchange,
            body: JSONObject,
            reply: JSONObject,
            pubkeyFromBody: Boolean = false
        ) {
            val fpr = body.optString("fpr")
            val nonce = body.optString("nonce")
            val sig = body.optString("sig")
            val armored = if (pubkeyFromBody) body.optString("pubkey") else identity.armoredPublicKey
            if (fpr != identity.fingerprint || nonce != NONCE || !verifies(sig, nonce, armored)) {
                respond(exchange, 403, JSONObject().put("error", "bad_signature"))
                return
            }
            respond(exchange, 200, reply)
        }

        private fun verifies(armoredSig: String, nonce: String, armoredKey: String): Boolean {
            return try {
                val sigBytes = CPArmor.dearmor(armoredSig) ?: return false
                val factory = BcPGPObjectFactory(PGPUtil.getDecoderStream(ByteArrayInputStream(sigBytes)))
                val signature = (factory.nextObject() as PGPSignatureList)[0]
                val ring = PGPPublicKeyRing(
                    PGPUtil.getDecoderStream(ByteArrayInputStream(CPArmor.dearmor(armoredKey)!!)),
                    BcKeyFingerprintCalculator()
                )
                signature.init(BcPGPContentVerifierBuilderProvider(), ring.getPublicKey(signature.keyID))
                signature.update(nonce.toByteArray(Charsets.UTF_8))
                signature.verify()
            } catch (e: Exception) {
                false
            }
        }

        private fun respond(exchange: HttpExchange, status: Int, body: JSONObject) {
            val bytes = body.toString().toByteArray(Charsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
    }

    // ── Tests ──────────────────────────────────────────────────────────

    @Test
    fun registerDevicePassesRelaySignatureVerification() = runBlocking {
        client.registerDevice(label = "Pixel test")
        val body = seen["/v1/register-device"]!!
        assertEquals(identity.fingerprint, body.getString("fpr"))
        assertEquals(DEVICE_ID, body.getString("device_id"))
        assertEquals("Pixel test", body.getString("label"))
        assertTrue(body.getString("pubkey").contains("BEGIN PGP PUBLIC KEY BLOCK"))
        // Reaching here at all means the stub verified the detached signature.
    }

    @Test
    fun sendCarriesEnvelopeAndVerifiedAuth() = runBlocking {
        val envelope = byteArrayOf(0x43, 0x50, 0x4E, 0x31, 0, 1, 2, -1)
        val to = Fingerprint.from("08108C383A81C409F3E28757397AC5A52B6C5A8D")!!
        val response = client.send(envelope, to, expiresAt = 1_888_888_888L)
        assertEquals("m-123", response.messageId)
        assertEquals(1_999_999_999L, response.expiresAt)

        val body = seen["/v1/send"]!!
        assertEquals(to.hex, body.getString("to_fpr"))
        assertEquals(1_888_888_888L, body.getLong("expires_at"))
        assertArrayEquals(envelope, Base64.Default.decode(body.getString("envelope")))
    }

    @Test
    fun inboxParsesSnakeCaseMessages() = runBlocking {
        val messages = client.inbox()
        assertEquals(2, messages.size)
        assertEquals("a1", messages[0].messageId)
        assertArrayEquals("first".toByteArray(), Base64.Default.decode(messages[0].envelope))
        assertEquals("2026-07-08 12:00:00", messages[0].receivedAt)
        assertEquals("2026-07-15 12:00:05", messages[1].expiresAt)
        assertEquals(DEVICE_ID, seen["/v1/inbox"]!!.getString("device_id"))
    }

    @Test
    fun ackSendsIdsAndReturnsCount() = runBlocking {
        val acked = client.ack(listOf("a1", "a2", "a3"))
        assertEquals(3, acked)
        val ids = seen["/v1/ack"]!!.getJSONArray("message_ids")
        assertEquals(listOf("a1", "a2", "a3"), (0 until ids.length()).map { ids.getString(it) })
    }

    @Test
    fun registerPushCarriesTokenAndPlatform() = runBlocking {
        client.registerPush("fcm-token-xyz")
        val body = seen["/v1/register-push"]!!
        assertEquals("fcm-token-xyz", body.getString("push_token"))
        assertEquals("fcm", body.getString("platform"))
        assertEquals(DEVICE_ID, body.getString("device_id"))
    }

    @Test
    fun pairingFlowParses() = runBlocking {
        val offer = client.pairOffer(identity.armoredPublicKey)
        assertEquals("0123456789abcdef0123456789abcdef", offer.token)
        assertEquals(600L, offer.expiresIn)

        val accept = client.pairAccept(offer.token, identity.armoredPublicKey)
        assertEquals(identity.fingerprint, accept.offererFpr)
        assertTrue(accept.offererPubkey.contains("BEGIN PGP PUBLIC KEY BLOCK"))

        val status = client.pairStatus(offer.token)
        assertEquals("pending", status.state)
        assertNull(status.responderFpr)
        assertNull(status.responderPubkey)
    }

    @Test
    fun reportOmitsEmptyOptionals() = runBlocking {
        client.submitReport(
            reportedFingerprint = Fingerprint.from(identity.fingerprint)!!,
            category = "spam",
            description = "",
            content = null
        )
        val body = seen["/v1/report"]!!
        assertEquals("spam", body.getString("category"))
        assertTrue(!body.has("description"))
        assertTrue(!body.has("content"))
    }

    @Test
    fun non200MapsToRelayException() {
        try {
            runBlocking {
                // Reuse the transport through a client pointed at the failing path
                // by way of a normal endpoint the stub answers with 403.
                RelayClient(
                    baseURL = "http://127.0.0.1:${server.address.port}/v1/forbidden",
                    crypto = PonyCryptoEngine(
                        fingerprint = Fingerprint.from(identity.fingerprint)!!,
                        secretKey = identity.secretKey,
                        armored = identity.armoredPublicKey,
                        publicKeyResolver = { null }
                    ),
                    deviceID = DEVICE_ID
                ).inbox()
            }
            fail("Expected RelayException")
        } catch (e: RelayException) {
            assertEquals(403, e.status)
            assertEquals("unknown_fingerprint", e.code)
        }
    }
}
