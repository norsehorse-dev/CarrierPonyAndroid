// ChatStore.kt
// CarrierPony Android
//
// The messaging heart, ported method-for-method from iOS
// Core/Messaging/ChatStore.swift: receive-and-file with dedup and expiry,
// send with local echo and optional self-copy for multi-device sync, read
// receipts and deletes as encrypted control messages to self, display-name
// exchange as encrypted control messages to peers, abuse reporting, demo-mode
// auto-replies, and per-identity JSON persistence so threads survive
// relaunch.
//
// Concurrency: iOS confines this to the main actor; here a Mutex serializes
// state mutation while relay I/O runs outside the lock, and the UI observes
// the conversations StateFlow. Polling is a coroutine on the supplied scope,
// started/stopped from the app lifecycle exactly like iOS start()/stop().

package com.carrierpony.app.messaging

import com.carrierpony.app.AppConfig
import com.carrierpony.app.DemoMode
import com.carrierpony.app.attachments.AttachmentStore
import com.carrierpony.app.crypto.CryptoEngine
import com.carrierpony.app.crypto.Fingerprint
import com.carrierpony.app.crypto.PublicKey
import com.carrierpony.app.envelope.CPN1
import com.carrierpony.app.envelope.Manifest
import com.carrierpony.app.envelope.Threading
import com.carrierpony.app.relay.RelayClient
import com.carrierpony.app.relay.RelayException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import kotlin.io.encoding.Base64

class ChatStore(
    private val identity: Fingerprint,
    private val relay: RelayClient,
    private val crypto: CryptoEngine,
    private val contacts: () -> List<Contact>,
    private val multiDevice: Boolean = true,
    private val defaultTTL: Long = 30L * 86_400,
    private val updatePeerName: (Fingerprint, String?) -> Unit = { _, _ -> },
    storageDir: File,
    private val scope: CoroutineScope
) {

    private val factory = EnvelopeFactory(crypto)
    private val fileURL = File(storageDir, "carrierpony-conversations-${identity.hex}.json")

    private val _conversations = kotlinx.coroutines.flow.MutableStateFlow<Map<String, Conversation>>(emptyMap())
    val conversations: kotlinx.coroutines.flow.StateFlow<Map<String, Conversation>> = _conversations

    private val _lastError = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    val lastError: kotlinx.coroutines.flow.StateFlow<String?> = _lastError

    private val seenMessageIDs = mutableSetOf<String>()
    private var pollJob: Job? = null
    private val mutex = Mutex()

    companion object {
        /** Diagnostics sink. A no-op by default (JVM tests); the app wires it
         *  to logcat so wire-level failures are visible via
         *  `adb logcat -s CarrierPony`. */
        var diagnostics: (String, Throwable?) -> Unit = { _, _ -> }
    }

    init {
        load()
    }

    // ── Lifecycle ──────────────────────────────────────────────────────

    suspend fun start(pollIntervalSeconds: Long = 5) {
        try {
            relay.registerDevice(label = "android")
        } catch (e: Exception) {
            _lastError.value = describe(e)
        }
        refresh()
        pollJob?.cancel()
        pollJob = scope.launch {
            while (isActive) {
                delay(pollIntervalSeconds * 1000)
                refresh()
            }
        }
    }

    fun stop() {
        pollJob?.cancel()
        pollJob = null
    }

    /** Clear all conversations from memory and disk. Used on sign-out so a
     *  device doesn't keep plaintext message history after its identity is gone. */
    fun purge() {
        for (conversation in _conversations.value.values) {
            for (message in conversation.messages) {
                for (attachment in message.attachments) {
                    AttachmentStore.delete(attachment.localPath)
                }
            }
        }
        _conversations.value = emptyMap()
        synchronized(seenMessageIDs) { seenMessageIDs.clear() }
        fileURL.delete()
    }

    fun clearError() {
        _lastError.value = null
    }

    // ── Receive ────────────────────────────────────────────────────────

    /** Envelopes that failed to open get retried on later polls instead of
     *  being acked (consumed) immediately: the common cause is a message
     *  racing pairing — the sender's key lands seconds later and the retry
     *  succeeds. The bound stops poison envelopes from looping forever. */
    private val failedOpenCounts = mutableMapOf<String, Int>()
    private val maxOpenAttempts = 10

    suspend fun refresh() {
        try {
            val inbox = relay.inbox()
            val ackIDs = mutableListOf<String>()
            mutex.withLock {
                for (item in inbox) {
                    val envelope = try {
                        Base64.Default.decode(item.envelope)
                    } catch (e: Exception) {
                        ackIDs.add(item.messageId)   // unparseable; drop it
                        continue
                    }
                    val opened = handleLocked(envelope)
                    if (opened) {
                        ackIDs.add(item.messageId)
                        failedOpenCounts.remove(item.messageId)
                    } else {
                        val failures = (failedOpenCounts[item.messageId] ?: 0) + 1
                        if (failures >= maxOpenAttempts) {
                            diagnostics("giving up on envelope ${item.messageId} after $failures failed opens", null)
                            ackIDs.add(item.messageId)
                            failedOpenCounts.remove(item.messageId)
                        } else {
                            failedOpenCounts[item.messageId] = failures
                        }
                    }
                }
            }
            if (ackIDs.isNotEmpty()) {
                relay.ack(ackIDs)
            }
            mutex.withLock { pruneExpiredLocked() }
        } catch (e: Exception) {
            diagnostics("refresh failed: ${e.javaClass.simpleName}: ${e.message}", e)
            _lastError.value = describe(e)
        }
    }

    /** @return true when the envelope was consumed (opened, or a duplicate /
     *  expired message we intentionally skip); false when opening failed and a
     *  retry on a later poll might succeed. */
    private fun handleLocked(envelope: ByteArray): Boolean {
        val incoming = try {
            factory.open(envelope)
        } catch (e: Exception) {
            // A common cause is the sender not being paired on this device, so
            // their signature can't be verified. Surface it instead of dropping
            // the message silently — and log the precise failure for wire
            // debugging (the exception names the exact layer that refused).
            val chain = generateSequence<Throwable>(e) { it.cause }
                .joinToString(" <- ") { "${it.javaClass.simpleName}: ${it.message}" }
            diagnostics("envelope open failed (${envelope.size} bytes): $chain", e)
            _lastError.value = "Couldn't open an incoming message. Is the sender paired on this device?"
            return false
        }
        val manifest = incoming.manifest

        if (seenMessageIDs.contains(manifest.messageID)) return true
        if (manifest.expiresAt <= now) {
            seenMessageIDs.add(manifest.messageID)
            return true
        }

        val senderIsSelf = incoming.sender == identity
        when {
            senderIsSelf && manifest.type == "control" -> applyControlLocked(incoming)
            senderIsSelf && manifest.type == "message" -> {
                // Self-copy of one of my own sent messages, synced from another device.
                val peer = resolvePeer(incoming, senderIsSelf = true) ?: return true
                fileLocked(incoming, peer, MessageDirection.OUTGOING)
            }
            !senderIsSelf && manifest.type == "message" ->
                fileLocked(incoming, incoming.sender, MessageDirection.INCOMING)
            !senderIsSelf && manifest.type == "control" ->
                // The only control a peer may send is a profile update (their display
                // name). It touches only how we label them, never our own state.
                applyContactProfileLocked(incoming)
            else -> return true   // Others cannot control my state; rejected-by-policy is consumed, not retried.
        }
        seenMessageIDs.add(manifest.messageID)
        return true
    }

    private fun resolvePeer(incoming: IncomingMessage, senderIsSelf: Boolean): Fingerprint? {
        if (senderIsSelf) {
            // A self-copy is addressed to our own key, so manifest.to is our own
            // fingerprint — useless as a peer. Only take the shortcut when `to`
            // names someone else; otherwise fall through to the thread match,
            // which recovers the real peer from the contact list. (iOS note:
            // resolvePeer takes `to` unconditionally, so a thread first created
            // by a synced self-copy files under peer = self — same patch applies.)
            incoming.manifest.to
                ?.let { Fingerprint.from(it) }
                ?.takeIf { it != identity }
                ?.let { return it }
        }
        // Fallback: match the thread against known contacts.
        val thread = incoming.manifest.threadID
        return contacts().firstOrNull {
            Threading.pairwise(identity.hex, it.fingerprint.hex) == thread
        }?.fingerprint
    }

    private fun fileLocked(incoming: IncomingMessage, peer: Fingerprint, direction: MessageDirection) {
        val manifest = incoming.manifest
        val message = ChatMessage(
            id = manifest.messageID,
            threadID = manifest.threadID,
            peer = peer,
            direction = direction,
            text = incoming.text,
            attachments = incoming.files.map { (part, data) ->
                val mime = part.mime ?: "application/octet-stream"
                val filename = part.filename ?: "file"
                ChatMessage.Attachment(
                    id = UUID.randomUUID().toString(),
                    filename = filename,
                    mime = mime,
                    size = data.size.toLong(),
                    localPath = AttachmentStore.save(data, filename)
                )
            },
            sentAt = manifest.sentAt,
            expiresAt = manifest.expiresAt,
            isRead = direction == MessageDirection.OUTGOING
        )
        appendToConversationLocked(message, peer)
    }

    // ── Send ───────────────────────────────────────────────────────────

    suspend fun send(
        text: String?,
        attachments: List<OutgoingMessage.Attachment> = emptyList(),
        to: Contact,
        ttl: Long? = null
    ) {
        val totalBytes = attachments.sumOf { it.data.size.toLong() }
        if (totalBytes > AppConfig.maxAttachmentBytes) {
            val mb = AppConfig.maxAttachmentBytes / (1024 * 1024)
            _lastError.value = "That attachment is too large to send (limit $mb MB)."
            return
        }

        val threadID = Threading.pairwise(identity.hex, to.fingerprint.hex)
        val expiresAt = now + (ttl ?: defaultTTL)
        val messageID = UUID.randomUUID().toString().uppercase()
        val outgoing = OutgoingMessage(threadID = threadID, text = text, attachments = attachments, expiresAt = expiresAt)

        // Persist attachment bytes to disk for the local echo (kept out of the
        // conversation store and out of memory).
        val storedAttachments = attachments.map { attachment ->
            ChatMessage.Attachment(
                id = UUID.randomUUID().toString(),
                filename = attachment.filename,
                mime = attachment.mime,
                size = attachment.data.size.toLong(),
                localPath = AttachmentStore.save(attachment.data, attachment.filename)
            )
        }

        // Show immediately on this device.
        val local = ChatMessage(
            id = messageID,
            threadID = threadID,
            peer = to.fingerprint,
            direction = MessageDirection.OUTGOING,
            text = text,
            attachments = storedAttachments,
            sentAt = now,
            expiresAt = expiresAt,
            isRead = true
        )
        mutex.withLock {
            seenMessageIDs.add(messageID)
            appendToConversationLocked(local, to.fingerprint, peerName = to.name)
        }

        if (to.fingerprint == DemoMode.fingerprint) {
            demoAutoReply(hadAttachment = attachments.isNotEmpty())
            return
        }

        try {
            val envelope = factory.build(outgoing, to.publicKey, messageID)
            relay.send(envelope, to.fingerprint, expiresAt)
            if (multiDevice) {
                sendSelfCopy(outgoing, messageID)
            }
        } catch (e: Exception) {
            _lastError.value = describe(e)
        }
    }

    private suspend fun sendSelfCopy(outgoing: OutgoingMessage, messageID: String) {
        val selfKey = ownPublicKey() ?: return
        val envelope = factory.build(outgoing, selfKey, messageID)
        relay.send(envelope, identity, outgoing.expiresAt)
    }

    // ── Reporting ──────────────────────────────────────────────────────

    /** Text of recent messages received from a peer, oldest-first, for an abuse
     *  report. Only incoming text is included; attachment bytes are never shared. */
    fun recentIncomingText(peer: Fingerprint, limit: Int = 20): String? {
        val texts = _conversations.value.values
            .flatMap { it.messages }
            .filter { it.peer == peer && it.direction == MessageDirection.INCOMING }
            .sortedByDescending { it.sentAt }
            .take(limit)
            .mapNotNull { it.text?.trim()?.takeIf(String::isNotEmpty) }
        return if (texts.isEmpty()) null else texts.reversed().joinToString("\n")
    }

    suspend fun submitReport(contact: Contact, category: String, description: String?, includeContent: Boolean) {
        val content = if (includeContent) recentIncomingText(contact.fingerprint) else null
        relay.submitReport(
            reportedFingerprint = contact.fingerprint,
            category = category,
            description = description,
            content = content
        )
    }

    // ── Demo mode (App Review) ─────────────────────────────────────────

    /** Inject a message that appears to come from the simulated demo contact. */
    suspend fun injectDemoIncoming(text: String, attachment: ChatMessage.Attachment? = null) {
        val threadID = Threading.pairwise(identity.hex, DemoMode.fingerprint.hex)
        val message = ChatMessage(
            id = UUID.randomUUID().toString(),
            threadID = threadID,
            peer = DemoMode.fingerprint,
            direction = MessageDirection.INCOMING,
            text = text,
            attachments = attachment?.let { listOf(it) } ?: emptyList(),
            sentAt = now,
            expiresAt = now + defaultTTL,
            isRead = false
        )
        mutex.withLock {
            appendToConversationLocked(message, DemoMode.fingerprint, peerName = DemoMode.peerName)
        }
    }

    private suspend fun demoAutoReply(hadAttachment: Boolean) {
        delay(1200)
        if (hadAttachment) {
            val data = "This file came back from the CarrierPony demo contact. It was delivered the same encrypted way your file was."
                .toByteArray(Charsets.UTF_8)
            val attachment = ChatMessage.Attachment(
                id = UUID.randomUUID().toString(),
                filename = "demo-reply.txt",
                mime = "text/plain",
                size = data.size.toLong(),
                localPath = AttachmentStore.save(data, "demo-reply.txt")
            )
            injectDemoIncoming(
                text = "Got your file. It was encrypted on your device and decrypted here. Here's one back so you can see file transfer both ways.",
                attachment = attachment
            )
        } else {
            injectDemoIncoming(
                text = "Received and decrypted on-device. In a real conversation this reply would come from the person you paired with. Try attaching a file next, or open the ••• menu to see Report and Block."
            )
        }
    }

    // ── Read / control ─────────────────────────────────────────────────

    suspend fun markRead(threadID: String) {
        val unread = mutex.withLock {
            val conversation = _conversations.value[threadID] ?: return
            val unread = conversation.messages.filter { it.direction == MessageDirection.INCOMING && !it.isRead }
            if (unread.isEmpty()) return
            val updated = conversation.copy(messages = conversation.messages.map { it.copy(isRead = true) })
            _conversations.value = _conversations.value + (threadID to updated)
            persistLocked()
            unread
        }
        if (multiDevice) {
            for (message in unread) {
                emitControl(op = "read", targetMessageID = message.id, threadID = threadID)
            }
        }
    }

    private fun applyControlLocked(incoming: IncomingMessage) {
        val control = controlPayload(incoming) ?: return
        when (control.op) {
            "read" -> setReadLocked(control.targetMessageID)
            "delete" -> deleteMessageLocked(control.targetMessageID)
        }
    }

    // ── Profile (display name exchange) ────────────────────────────────

    /** Send my current display name to a contact as an encrypted control message.
     *  If the name is null/empty (anonymous), nothing is sent. */
    suspend fun sendProfile(name: String?, to: Contact) {
        val displayName = name?.trim()?.takeIf { it.isNotEmpty() } ?: return
        val threadID = Threading.pairwise(identity.hex, to.fingerprint.hex)
        val body = ControlOp(op = "profile", targetMessageID = "", at = now, name = displayName)
        val controlMessage = OutgoingMessage(
            threadID = threadID,
            text = null,
            attachments = listOf(OutgoingMessage.Attachment("control", "application/json", body.encoded())),
            expiresAt = now + 86_400
        )
        try {
            val messageID = UUID.randomUUID().toString().uppercase()
            mutex.withLock { seenMessageIDs.add(messageID) }
            val envelope = buildControl(controlMessage, to.publicKey, messageID)
            relay.send(envelope, to.fingerprint, controlMessage.expiresAt)
        } catch (e: Exception) {
            _lastError.value = describe(e)
        }
    }

    private fun applyContactProfileLocked(incoming: IncomingMessage) {
        val control = controlPayload(incoming) ?: return
        if (control.op != "profile") return
        val name = control.name?.trim()?.takeIf { it.isNotEmpty() }
        // Update the stored contact (source of truth for names).
        updatePeerName(incoming.sender, name)
        // Reflect immediately on any existing thread.
        val threadID = Threading.pairwise(identity.hex, incoming.sender.hex)
        _conversations.value[threadID]?.let { conversation ->
            _conversations.value = _conversations.value +
                (threadID to conversation.copy(peerName = name ?: conversation.peerName))
            persistLocked()
        }
    }

    private suspend fun emitControl(op: String, targetMessageID: String, threadID: String) {
        val selfKey = ownPublicKey() ?: return
        val body = ControlOp(op = op, targetMessageID = targetMessageID, at = now)
        val controlMessage = OutgoingMessage(
            threadID = threadID,
            text = null,
            attachments = listOf(OutgoingMessage.Attachment("control", "application/json", body.encoded())),
            expiresAt = now + 86_400
        )
        try {
            val messageID = UUID.randomUUID().toString().uppercase()
            mutex.withLock { seenMessageIDs.add(messageID) }
            val envelope = buildControl(controlMessage, selfKey, messageID)
            relay.send(envelope, identity, controlMessage.expiresAt)
        } catch (e: Exception) {
            _lastError.value = describe(e)
        }
    }

    private fun buildControl(message: OutgoingMessage, recipient: PublicKey, messageID: String): ByteArray {
        // A control message is a normal envelope with manifest.type == "control".
        val parts = message.attachments.map { it.data }
        val meta = listOf(Manifest.Part(
            kind = "control",
            mime = "application/json",
            size = parts.firstOrNull()?.size?.toLong()
        ))
        val manifest = Manifest(
            v = 1,
            type = "control",
            messageID = messageID,
            threadID = message.threadID,
            to = recipient.fingerprint.hex,
            sentAt = now,
            expiresAt = message.expiresAt,
            parts = meta
        )
        val container = CPN1.encode(manifest.encoded(), parts)
        return crypto.signAndEncrypt(container, recipient)
    }

    class ControlOp(
        val op: String,
        val targetMessageID: String,
        val at: Long,
        val name: String? = null
    ) {
        fun encoded(): ByteArray {
            val json = JSONObject()
            json.put("op", op)
            json.put("target_message_id", targetMessageID)
            json.put("at", at)
            if (name != null) json.put("name", name)
            return json.toString().toByteArray(Charsets.UTF_8)
        }

        companion object {
            fun decode(data: ByteArray): ControlOp? = try {
                val json = JSONObject(String(data, Charsets.UTF_8))
                ControlOp(
                    op = json.getString("op"),
                    targetMessageID = json.optString("target_message_id"),
                    at = json.optLong("at"),
                    name = json.optString("name").takeIf { it.isNotEmpty() }
                )
            } catch (e: Exception) {
                null
            }
        }
    }

    private fun controlPayload(incoming: IncomingMessage): ControlOp? {
        val payload = incoming.files.firstOrNull()?.second
            ?: incoming.text?.toByteArray(Charsets.UTF_8)
            ?: return null
        return ControlOp.decode(payload)
    }

    // ── Helpers ────────────────────────────────────────────────────────

    private fun appendToConversationLocked(message: ChatMessage, peer: Fingerprint, peerName: String? = null) {
        val existing = _conversations.value[message.threadID]
        if (existing != null) {
            if (existing.messages.any { it.id == message.id }) return
            _conversations.value = _conversations.value + (message.threadID to existing.copy(
                messages = existing.messages + message,
                peerName = peerName ?: existing.peerName
            ))
        } else {
            _conversations.value = _conversations.value + (message.threadID to Conversation(
                threadID = message.threadID,
                peer = peer,
                peerName = peerName ?: contactName(peer),
                messages = listOf(message)
            ))
        }
        persistLocked()
    }

    private fun setReadLocked(messageID: String) {
        for ((threadID, conversation) in _conversations.value) {
            if (conversation.messages.any { it.id == messageID }) {
                _conversations.value = _conversations.value + (threadID to conversation.copy(
                    messages = conversation.messages.map { if (it.id == messageID) it.copy(isRead = true) else it }
                ))
                persistLocked()
                return
            }
        }
    }

    private fun deleteMessageLocked(id: String) {
        var updated = _conversations.value
        for ((threadID, conversation) in updated) {
            for (message in conversation.messages.filter { it.id == id }) {
                for (attachment in message.attachments) {
                    AttachmentStore.delete(attachment.localPath)
                }
            }
            updated = updated + (threadID to conversation.copy(
                messages = conversation.messages.filter { it.id != id }
            ))
        }
        _conversations.value = updated
        persistLocked()
    }

    private fun pruneExpiredLocked() {
        val cutoff = now
        var updated = _conversations.value
        for ((threadID, conversation) in updated) {
            val kept = conversation.messages.filter { it.expiresAt > cutoff }
            if (kept.size != conversation.messages.size) {
                updated = updated + (threadID to conversation.copy(messages = kept))
            }
        }
        _conversations.value = updated
        persistLocked()
    }

    // ── Persistence ────────────────────────────────────────────────────
    //
    // Conversations and messages are kept on disk so threads survive relaunch.
    // Stored per-identity (the filename carries the identity fingerprint), so a
    // different identity on the same device sees its own history and never the
    // previous one's. Field names match the iOS snapshot for symmetry.

    private fun persistLocked() {
        val conversationsArray = JSONArray()
        for (conversation in _conversations.value.values) {
            val messagesArray = JSONArray()
            for (message in conversation.messages) {
                val attachmentsArray = JSONArray()
                for (attachment in message.attachments) {
                    attachmentsArray.put(JSONObject()
                        .put("id", attachment.id)
                        .put("filename", attachment.filename)
                        .put("mime", attachment.mime)
                        .put("size", attachment.size)
                        .put("localPath", attachment.localPath))
                }
                val messageJson = JSONObject()
                    .put("id", message.id)
                    .put("threadID", message.threadID)
                    .put("peer", message.peer.hex)
                    .put("direction", if (message.direction == MessageDirection.INCOMING) "in" else "out")
                    .put("attachments", attachmentsArray)
                    .put("sentAt", message.sentAt)
                    .put("expiresAt", message.expiresAt)
                    .put("isRead", message.isRead)
                if (message.text != null) messageJson.put("text", message.text)
                messagesArray.put(messageJson)
            }
            val conversationJson = JSONObject()
                .put("threadID", conversation.threadID)
                .put("peer", conversation.peer.hex)
                .put("messages", messagesArray)
            if (conversation.peerName != null) conversationJson.put("peerName", conversation.peerName)
            conversationsArray.put(conversationJson)
        }
        val snapshot = JSONObject()
            .put("conversations", conversationsArray)
            .put("seen", JSONArray(seenMessageIDs.toList()))
        try {
            fileURL.parentFile?.mkdirs()
            fileURL.writeText(snapshot.toString())
        } catch (e: Exception) {
            // Mirrors iOS's try? — persistence failure never crashes the app.
        }
    }

    private fun load() {
        val text = try {
            fileURL.takeIf { it.exists() }?.readText()
        } catch (e: Exception) {
            null
        } ?: return
        try {
            val snapshot = JSONObject(text)
            val restored = mutableMapOf<String, Conversation>()
            val conversationsArray = snapshot.getJSONArray("conversations")
            for (i in 0 until conversationsArray.length()) {
                val stored = conversationsArray.getJSONObject(i)
                val peer = Fingerprint.from(stored.getString("peer")) ?: continue
                val messagesArray = stored.getJSONArray("messages")
                val messages = mutableListOf<ChatMessage>()
                for (j in 0 until messagesArray.length()) {
                    val m = messagesArray.getJSONObject(j)
                    val messagePeer = Fingerprint.from(m.getString("peer")) ?: continue
                    val attachmentsArray = m.getJSONArray("attachments")
                    val attachments = (0 until attachmentsArray.length()).map { k ->
                        val a = attachmentsArray.getJSONObject(k)
                        ChatMessage.Attachment(
                            id = a.getString("id"),
                            filename = a.getString("filename"),
                            mime = a.getString("mime"),
                            size = a.getLong("size"),
                            localPath = a.getString("localPath")
                        )
                    }
                    messages.add(ChatMessage(
                        id = m.getString("id"),
                        threadID = m.getString("threadID"),
                        peer = messagePeer,
                        direction = if (m.getString("direction") == "in") MessageDirection.INCOMING else MessageDirection.OUTGOING,
                        text = if (m.has("text")) m.getString("text") else null,
                        attachments = attachments,
                        sentAt = m.getLong("sentAt"),
                        expiresAt = m.getLong("expiresAt"),
                        isRead = m.getBoolean("isRead")
                    ))
                }
                restored[stored.getString("threadID")] = Conversation(
                    threadID = stored.getString("threadID"),
                    peer = peer,
                    peerName = if (stored.has("peerName")) stored.getString("peerName") else null,
                    messages = messages
                )
            }
            _conversations.value = restored
            val seenArray = snapshot.getJSONArray("seen")
            seenMessageIDs.clear()
            for (i in 0 until seenArray.length()) {
                seenMessageIDs.add(seenArray.getString(i))
            }
        } catch (e: Exception) {
            // A corrupt snapshot loads as empty rather than crashing.
        }
    }

    private fun ownPublicKey(): PublicKey? = try {
        PublicKey(identity, crypto.armoredPublicKey())
    } catch (e: Exception) {
        null
    }

    private fun contactName(fingerprint: Fingerprint): String? =
        contacts().firstOrNull { it.fingerprint == fingerprint }?.name

    private val now: Long get() = System.currentTimeMillis() / 1000

    private fun describe(error: Exception): String {
        if (error is RelayException) {
            return "relay ${error.status}${error.code?.let { ": $it" } ?: ""}"
        }
        return error.message ?: error.javaClass.simpleName
    }
}
