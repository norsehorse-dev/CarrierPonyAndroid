package com.carrierpony.app

import com.carrierpony.app.nostr.NostrEventBuilder
import com.carrierpony.app.nostr.NostrKind
import com.carrierpony.app.nostr.NostrRelayClient
import com.carrierpony.app.nostr.Secp256k1
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * End-to-end M2 proof against a live relay. Skipped unless NOSTR_LIVE is set, since it
 * needs network and a working public relay. Override the relay with NOSTR_RELAY.
 *   NOSTR_LIVE=1 ./gradlew testDebugUnitTest --tests "com.carrierpony.app.NostrRoundTripTest"
 */
class NostrRoundTripTest {

    @Test fun publishAndReadBack() {
        assumeTrue("set NOSTR_LIVE=1 to run", System.getenv("NOSTR_LIVE") != null)
        val relayUrl = System.getenv("NOSTR_RELAY") ?: "wss://relay.damus.io"

        val addr = "cpmbx" + java.util.UUID.randomUUID().toString().replace("-", "")
        val seckey = Secp256k1.randomSecretKey()
        val event = NostrEventBuilder.build(
            seckey, System.currentTimeMillis() / 1000, NostrKind.MAILBOX,
            listOf(listOf("t", addr)), "aGVsbG8=")

        val client = NostrRelayClient(relayUrl)
        val got = CountDownLatch(1)
        var receivedId: String? = null
        var okAccepted: Boolean? = null

        client.onEvent = { _, ev -> if (ev.optString("id") == event.id) { receivedId = ev.optString("id"); got.countDown() } }
        client.onOk = { id, accepted, _ -> if (id == event.id) okAccepted = accepted }

        client.connect()
        val reader = thread { client.readLoop() }
        try {
            client.subscribe("m2", NostrRelayClient.idFilter(event.id))
            Thread.sleep(300)
            client.publish(event.json())
            val arrived = got.await(20, TimeUnit.SECONDS)
            assertEquals("relay did not return the published event (ok=$okAccepted)", true, arrived)
            assertEquals(event.id, receivedId)
        } finally {
            client.close()
            reader.join(1000)
        }
    }
}
