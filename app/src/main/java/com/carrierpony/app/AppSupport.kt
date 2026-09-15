// AppSupport.kt
// CarrierPony Android
//
// App-level configuration and the App Review demo constants, ported from iOS
// Core/App/AppSupport.swift and Core/App/DemoMode.swift. (iOS's PGPArmor
// helper is not ported — CPArmor in the core does that job here.)

package com.carrierpony.app

import android.content.Context
import android.net.Uri
import com.carrierpony.app.crypto.Fingerprint
import java.util.UUID

object AppConfig {

    /** The default CarrierPony relay. Used unless the user has pointed the app
     *  at a self-hosted relay in Settings > Relay. */
    const val defaultRelayBaseURL = "https://api.carrierpony.com"

    private const val relayURLKey = "cp.relayBaseURL"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences("cp.prefs", Context.MODE_PRIVATE)

    /** The relay this install talks to. Defaults to the CarrierPony relay; a
     *  self-hoster can override it in Settings > Relay. The value is read once at
     *  launch when the network stack is built, so a change takes effect on the
     *  next launch. */
    fun relayBaseURL(context: Context): String {
        val stored = prefs(context).getString(relayURLKey, null)
        return if (stored != null && isValidRelayURL(stored)) normalizedRelayURL(stored)
               else defaultRelayBaseURL
    }

    /** True when the app is pointed at a relay other than the default. */
    fun usingCustomRelay(context: Context): Boolean {
        val stored = prefs(context).getString(relayURLKey, null) ?: return false
        return stored.isNotBlank() && normalizedRelayURL(stored) != defaultRelayBaseURL
    }

    /** Persist a custom relay base URL. Pass null, blank, or the default to clear
     *  the override and return to the CarrierPony relay. Returns false (storing
     *  nothing) if the URL is not a valid https origin. */
    fun setRelayBaseURL(context: Context, url: String?): Boolean {
        val p = prefs(context)
        if (url == null || url.isBlank()) {
            p.edit().remove(relayURLKey).apply()
            return true
        }
        val normalized = normalizedRelayURL(url)
        if (!isValidRelayURL(normalized)) return false
        if (normalized == defaultRelayBaseURL) p.edit().remove(relayURLKey).apply()
        else p.edit().putString(relayURLKey, normalized).apply()
        return true
    }

    /** Trim whitespace and any trailing slashes. The client appends "/v1/..."
     *  paths, so the stored base must not end in a slash. */
    fun normalizedRelayURL(url: String): String = url.trim().trimEnd('/')

    /** A relay URL must be an https origin with a host. http is refused because
     *  the challenge-response auth (nonce + signature) must not travel in clear. */
    fun isValidRelayURL(url: String): Boolean {
        val u = Uri.parse(normalizedRelayURL(url))
        return u.scheme?.lowercase() == "https" && !u.host.isNullOrEmpty()
    }

    /** Maximum total attachment bytes per message. The relay must accept an
     *  encrypted envelope somewhat larger than this (base64 of the encrypted
     *  container), so keep the relay's max_envelope_bytes above ~1.4x this. */
    const val maxAttachmentBytes = 50 * 1024 * 1024

    /** A stable per-install device identifier, minted once and persisted. The
     *  relay requires exactly 32 lowercase hex characters (^[0-9a-f]{32}$), so
     *  a hyphenated/uppercase UUID string won't pass — strip the hyphens and
     *  lowercase it, which yields the 32-hex form the relay was built against. */
    /** The CarrierPony push gateway. A relay you run yourself can't wake installs
     *  on its own; a user may opt in to be woken through this gateway. */
    const val gatewayBaseURL = "https://push.carrierpony.com"

    private const val gatewayEnabledKey = "cp.gatewayPush"
    private const val wakeTokenKey = "cp.wakeToken"

    fun gatewayPushEnabled(context: Context): Boolean =
        prefs(context).getBoolean(gatewayEnabledKey, false)

    fun setGatewayPushEnabled(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean(gatewayEnabledKey, on).apply()
    }

    private const val lanDirectKey = "cp.lanDirect"

    /** LAN-direct (2.1): opt-in, off by default. On lets the app identify known
     *  contacts on the local network and (M3b) deliver to them directly, exposing
     *  this device's IP to that contact. Off keeps everything on the relay. */
    fun lanDirectEnabled(context: Context): Boolean =
        prefs(context).getBoolean(lanDirectKey, false)

    fun setLanDirectEnabled(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean(lanDirectKey, on).apply()
    }

    private const val lanSkipRelayKey = "cp.lanSkipRelay"

    /** LAN-direct "skip the relay" (2.1 M4b): when on, a message delivered directly
     *  on the local network is NOT also sent through the relay - maximum privacy at
     *  the cost of the peer's other devices and offline delivery. Off by default. */
    fun lanDirectSkipRelay(context: Context): Boolean =
        prefs(context).getBoolean(lanSkipRelayKey, false)

    fun setLanDirectSkipRelay(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean(lanSkipRelayKey, on).apply()
    }

    private const val wanDirectKey = "cp.wanDirect"

    /** WAN-direct (2.2): opt-in, off by default. On, the app sets up direct P2P
     *  paths over the internet (STUN + authenticated UDP hole punching via
     *  PonyDirect) with the relay only introducing peers over sealed signaling;
     *  exposes each peer's IP to the other. Falls back to the relay when a path
     *  cannot be punched (no TURN). */
    fun wanDirectEnabled(context: Context): Boolean =
        prefs(context).getBoolean(wanDirectKey, false)

    fun setWanDirectEnabled(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean(wanDirectKey, on).apply()
    }

    private const val wanStunHostKey = "cp.wanStunHost"
    private const val wanStunPortKey = "cp.wanStunPort"

    /** STUN server host for hole punching. Empty means "use the relay's host": a
     *  self-hoster runs ponystun on the same box (UDP 3478). */
    fun wanStunHost(context: Context): String =
        prefs(context).getString(wanStunHostKey, "") ?: ""

    fun setWanStunHost(context: Context, host: String) {
        prefs(context).edit().putString(wanStunHostKey, host).apply()
    }

    /** STUN server UDP port. Defaults to 3478. */
    fun wanStunPort(context: Context): Int {
        val v = prefs(context).getInt(wanStunPortKey, 0)
        return if (v == 0) 3478 else v
    }

    private fun wakeTokenKeyFor(fpr: String?) = if (fpr == null) wakeTokenKey else "$wakeTokenKey.$fpr"

    fun storedWakeToken(context: Context, fpr: String? = null): String? =
        prefs(context).getString(wakeTokenKeyFor(fpr), null)

    fun setStoredWakeToken(context: Context, token: String?, fpr: String? = null) {
        val p = prefs(context)
        if (token == null) p.edit().remove(wakeTokenKeyFor(fpr)).apply()
        else p.edit().putString(wakeTokenKeyFor(fpr), token).apply()
    }

    fun deviceID(context: Context): String {
        val prefs = context.applicationContext.getSharedPreferences("cp.prefs", Context.MODE_PRIVATE)
        val existing = prefs.getString("cp.deviceID", null)
        if (existing != null && Regex("^[0-9a-f]{32}$").matches(existing)) {
            return existing
        }
        val id = UUID.randomUUID().toString().replace("-", "").lowercase()
        prefs.edit().putString("cp.deviceID", id).apply()
        return id
    }
}

// App Review demonstration mode.
//
// CarrierPony is peer-to-peer, so a single reviewer on one device cannot
// exchange messages without a second party. Entering the code below in
// "Enter an invite" pairs the reviewer with a simulated local contact that
// auto-replies (and echoes a file back), so the entire messaging and
// file-transfer flow can be verified on one device.
//
// This activates only when someone types the exact code. Real users never see
// it, and it uses no network. The activation flow (invite interception)
// arrives with the demo-mode phase; the constants live here because ChatStore
// short-circuits sends to the demo fingerprint.
object DemoMode {
    const val inviteCode = "CPDEMO-REVIEW"
    const val fingerprintHex = "DE300DE300DE300DE300DE300DE300DE300DE300"
    const val peerName = "CarrierPony Demo"

    val fingerprint: Fingerprint get() = Fingerprint.from(fingerprintHex)!!

    fun matches(input: String): Boolean {
        val core = input.uppercase().filter { it.isLetterOrDigit() }
        return core == "CPDEMOREVIEW"
    }
}
