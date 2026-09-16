package com.carrierpony.app.nostr

import com.carrierpony.app.crypto.Fingerprint
import com.carrierpony.app.messaging.Transport
import com.carrierpony.app.messaging.TransportCapabilities
import com.carrierpony.app.messaging.TransportID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Holds the live connections to the configured Nostr relays and publishes events,
 * resolving each publish once any relay OKs it (or a timeout). Each relay runs its
 * blocking read loop on its own IO coroutine. Mirrors the iOS NostrTransportManager.
 */
@OptIn(ExperimentalEncodingApi::class)
class NostrTransportManager {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var clients: List<NostrRelayClient> = emptyList()
    private var readers: List<Job> = emptyList()
    private val pending = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()

    /** True when at least one relay connection has been set up. */
    val isConnected: Boolean get() = clients.isNotEmpty()

    /** Connect to the given relays, replacing any existing connections. */
    fun start(relayUrls: List<String>) {
        stop()
        val cs = relayUrls.map { url ->
            NostrRelayClient(url).also { c ->
                c.onOk = { eventId, accepted, _ -> pending.remove(eventId)?.complete(accepted) }
            }
        }
        readers = cs.map { c ->
            scope.launch {
                try { c.connect(); c.readLoop() } catch (_: Exception) {}
            }
        }
        clients = cs
    }

    /** Drop all connections and fail any publish still waiting. */
    fun stop() {
        clients.forEach { try { it.close() } catch (_: Exception) {} }
        readers.forEach { it.cancel() }
        clients = emptyList()
        readers = emptyList()
        pending.values.forEach { it.complete(false) }
        pending.clear()
    }

    /** Publish a sealed envelope to [mailbox] as a kind-1314 event with a fresh
     *  ephemeral key. True once any relay accepts it, false on timeout or if no
     *  relay is connected. The pending waiter is registered before publishing, so
     *  an OK that arrives immediately is not missed. */
    suspend fun publish(envelope: ByteArray, mailbox: String, createdAt: Long): Boolean {
        val cs = clients
        if (cs.isEmpty()) return false
        val seckey = Secp256k1.randomSecretKey()
        val event = NostrEventBuilder.build(
            seckey, createdAt, NostrKind.MAILBOX,
            listOf(listOf("t", mailbox)), Base64.Default.encode(envelope))
        val deferred = CompletableDeferred<Boolean>()
        pending[event.id] = deferred
        val json = event.json()
        cs.forEach { c -> scope.launch { try { c.publish(json) } catch (_: Exception) {} } }
        return try {
            withTimeout(6000L) { deferred.await() }
        } catch (e: TimeoutCancellationException) {
            pending.remove(event.id)
            false
        }
    }
}

/**
 * A [Transport] over Nostr relays. Store-and-forward, best-effort, concurrent with
 * the relay. Delivers only to sealed-capable peers, since it needs the mailbox
 * address; a null mailbox just means no Nostr delivery. [enabled] reads the live
 * opt-in setting.
 */
class NostrTransport(
    private val manager: NostrTransportManager,
    private val enabled: () -> Boolean,
) : Transport {
    override val id = TransportID.NOSTR
    override val capabilities = TransportCapabilities(storeAndForward = true, revealsIP = true, worksOffline = false)

    override fun canReach(peer: Fingerprint): Boolean = enabled() && manager.isConnected

    override suspend fun send(envelope: ByteArray, to: Fingerprint, mailbox: String?, expiresAt: Long, silent: Boolean): Boolean {
        if (!enabled() || mailbox == null) return false
        return manager.publish(envelope, mailbox, System.currentTimeMillis() / 1000)
    }
}
