// PairingCaps.kt
// CarrierPony Android
//
// What an app tells the other side it can do, carried in an invite ("c") and
// in the offline key QR ("caps") as a short list of names.
//
// "v6" means this app can hold a contact whose identity is OpenPGP v6: it
// accepts a 64-character fingerprint, verifies v6 signatures and can encrypt
// to an ML-KEM-768 + X25519 subkey. Every app from 4.0 on says so, whatever
// kind of identity it has itself. Apps before 4.0 say nothing, and their
// parsers skip the field.
//
// The one rule built on it: a v6 identity does not pair with an app that
// cannot hold it. Such an app would add nothing on its side (it rejects the
// fingerprint outright), so this side would be left with a contact who can
// never answer. Refusing before the relay is asked also keeps the one-time
// token unused, so the same invite still works after the other person updates.
//
// A peer whose own fingerprint is v6 needs no cap: only 4.0 or later makes one.
//
// iOS carries the same field under the same keys; both platforms must read
// each other's.

package com.carrierpony.app.pairing

import com.carrierpony.app.crypto.Fingerprint
import org.json.JSONArray
import org.json.JSONObject

object PairingCaps {

    const val V6 = "v6"

    /** What this build supports. */
    val current: List<String> = listOf(V6)

    private const val MAX_CAPS = 16
    private val namePattern = Regex("^[a-z0-9_-]{1,16}$")

    /** True when a peer with [peerFingerprint] and [peerCaps] can hold a v6 contact. */
    fun peerSupportsV6(peerFingerprint: Fingerprint, peerCaps: List<String>?): Boolean =
        peerFingerprint.isV6 || peerCaps?.contains(V6) == true

    /** Whether the identity [mine] may pair with that peer. Only a v6 identity is ever refused. */
    fun canPair(mine: Fingerprint, peerFingerprint: Fingerprint, peerCaps: List<String>?): Boolean =
        !mine.isV6 || peerSupportsV6(peerFingerprint, peerCaps)

    fun toJson(caps: List<String>): JSONArray = JSONArray().apply { caps.forEach { put(it) } }

    /**
     * The caps under [key], or null when there are none. Never fails: a field
     * of the wrong shape, or a name that is not a short lowercase token, is
     * skipped, because a cap this build does not understand must not make an
     * invite unreadable.
     */
    fun fromJson(json: JSONObject, key: String): List<String>? {
        val array = json.optJSONArray(key) ?: return null
        val caps = (0 until minOf(array.length(), MAX_CAPS))
            .mapNotNull { array.opt(it) as? String }
            .filter { namePattern.matches(it) }
            .distinct()
        return caps.ifEmpty { null }
    }
}
