// AppSupport.kt
// CarrierPony Android
//
// App-level configuration and the App Review demo constants, ported from iOS
// Core/App/AppSupport.swift and Core/App/DemoMode.swift. (iOS's PGPArmor
// helper is not ported — CPArmor in the core does that job here.)

package com.carrierpony.app

import android.content.Context
import com.carrierpony.app.crypto.Fingerprint
import java.util.UUID

object AppConfig {

    /** The live CarrierPony relay. */
    const val relayBaseURL = "https://api.carrierpony.com"

    /** Maximum total attachment bytes per message. The relay must accept an
     *  encrypted envelope somewhat larger than this (base64 of the encrypted
     *  container), so keep the relay's max_envelope_bytes above ~1.4x this. */
    const val maxAttachmentBytes = 50 * 1024 * 1024

    /** A stable per-install device identifier, minted once and persisted. The
     *  relay requires exactly 32 lowercase hex characters (^[0-9a-f]{32}$), so
     *  a hyphenated/uppercase UUID string won't pass — strip the hyphens and
     *  lowercase it, which yields the 32-hex form the relay was built against. */
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
