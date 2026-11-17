// ContactRequestStore.kt
// CarrierPony Android
//
// Requests from people who added this identity by its public key and are
// waiting to be added back. A request is only ever stored after its envelope
// was checked against the key it carries (ChatStore), so what is here is a
// real key and a name its owner chose. Nothing here is a contact: no message
// from these people opens until the request is accepted.
//
// Anyone who has the public key can send a request, so the list is bounded:
// one request per person, a cap on how many are kept, names cut to length.
// Declining remembers the fingerprint, and a later request from the same
// person is dropped without a word. Adding their key yourself still works.
//
// Per account, like contacts: activate(fpr) points the store at that
// identity's file.

package com.carrierpony.app.messaging

import com.carrierpony.app.crypto.Fingerprint
import com.carrierpony.app.pairing.PairingCaps
import com.carrierpony.app.storage.AtRest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class ContactRequest(
    val fingerprint: Fingerprint,
    val armoredPublicKey: String,
    val name: String?,          // the name they gave, already cleaned
    val caps: List<String>?,    // what their app says it supports
    val receivedAt: Long        // epoch seconds
) {
    val id: String get() = fingerprint.hex
}

class ContactRequestStore(private val storageDir: File) {

    private var file: File? = null
    private val declined = LinkedHashSet<String>()

    private val _requests = MutableStateFlow<List<ContactRequest>>(emptyList())
    val requestsFlow: StateFlow<List<ContactRequest>> = _requests.asStateFlow()

    val requests: List<ContactRequest> get() = _requests.value

    @Synchronized
    fun activate(identityHex: String) {
        file = File(storageDir, "carrierpony-contact-requests-$identityHex.json")
        load()
    }

    /** Delete this account's file (used when an account is removed). */
    fun deleteFile(identityHex: String) {
        File(storageDir, "carrierpony-contact-requests-$identityHex.json").delete()
    }

    /** Keep a request. False when it was not kept: declined before, or the
     *  list is full. A second request from the same person replaces the first. */
    @Synchronized
    fun add(request: ContactRequest): Boolean {
        if (request.fingerprint.hex in declined) return false
        val others = _requests.value.filter { it.fingerprint != request.fingerprint }
        if (others.size >= MAX_REQUESTS) return false
        _requests.value = others + request.copy(name = cleanName(request.name))
        persist()
        return true
    }

    /** Drop a request without remembering anything (it was accepted, or the
     *  person became a contact some other way). */
    @Synchronized
    fun remove(fingerprint: Fingerprint) {
        val next = _requests.value.filter { it.fingerprint != fingerprint }
        if (next.size == _requests.value.size) return
        _requests.value = next
        persist()
    }

    /** Drop a request and ignore that person's requests from now on. */
    @Synchronized
    fun decline(fingerprint: Fingerprint) {
        _requests.value = _requests.value.filter { it.fingerprint != fingerprint }
        declined.add(fingerprint.hex)
        while (declined.size > MAX_DECLINED) declined.remove(declined.first())
        persist()
    }

    @Synchronized
    fun isDeclined(fingerprint: Fingerprint): Boolean = fingerprint.hex in declined

    /** Forget that someone was declined (you added their key yourself). */
    @Synchronized
    fun clearDeclined(fingerprint: Fingerprint) {
        if (declined.remove(fingerprint.hex)) persist()
    }

    // ── Persistence ────────────────────────────────────────────────────

    private fun load() {
        declined.clear()
        val text = try {
            file?.takeIf { it.exists() }?.let { AtRest.readText(it) }
        } catch (e: Exception) {
            null
        } ?: run { _requests.value = emptyList(); return }
        try {
            val root = JSONObject(text)
            val array = root.optJSONArray("requests") ?: JSONArray()
            _requests.value = (0 until array.length()).mapNotNull { index ->
                val record = array.optJSONObject(index) ?: return@mapNotNull null
                val fingerprint = Fingerprint.from(record.optString("fingerprint")) ?: return@mapNotNull null
                val armored = record.optString("armored").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                ContactRequest(
                    fingerprint = fingerprint,
                    armoredPublicKey = armored,
                    name = record.optString("name").takeIf { it.isNotEmpty() },
                    caps = PairingCaps.fromJson(record, "caps"),
                    receivedAt = record.optLong("receivedAt", 0L)
                )
            }.take(MAX_REQUESTS)
            val no = root.optJSONArray("declined") ?: JSONArray()
            for (index in 0 until no.length()) (no.opt(index) as? String)?.let { declined.add(it) }
        } catch (e: Exception) {
            _requests.value = emptyList()
        }
    }

    private fun persist() {
        val target = file ?: return
        val array = JSONArray()
        for (request in _requests.value) {
            val record = JSONObject()
            record.put("fingerprint", request.fingerprint.hex)
            record.put("armored", request.armoredPublicKey)
            if (request.name != null) record.put("name", request.name)
            if (!request.caps.isNullOrEmpty()) record.put("caps", PairingCaps.toJson(request.caps))
            record.put("receivedAt", request.receivedAt)
            array.put(record)
        }
        val no = JSONArray()
        for (hex in declined) no.put(hex)
        try {
            AtRest.writeText(target, JSONObject().put("requests", array).put("declined", no).toString())
        } catch (e: Exception) {
            // best-effort, as with contacts
        }
    }

    companion object {
        const val MAX_REQUESTS = 50
        const val MAX_DECLINED = 500
        const val MAX_NAME_LENGTH = 64

        /** A name as it will be shown: one line, no control characters, cut to
         *  length. Null when nothing is left. */
        fun cleanName(name: String?): String? =
            name?.filter { !it.isISOControl() }
                ?.trim()
                ?.take(MAX_NAME_LENGTH)
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
    }
}
