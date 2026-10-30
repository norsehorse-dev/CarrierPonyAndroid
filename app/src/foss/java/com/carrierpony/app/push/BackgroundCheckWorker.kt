// BackgroundCheckWorker.kt
// CarrierPony Android — FOSS FLAVOR
//
// The fallback when there is no UnifiedPush distributor: about every 15 minutes
// (Android may stretch that to save battery), ask the relay what is waiting for
// each account on this install, and post the generic "New message" notification
// when something new arrived.
//
// It only looks. Nothing is decrypted, acknowledged or written to the message
// store, so it can never race the app's own poll or lose a message; the app
// receives everything as usual when it is opened. The relay marks silent sends
// (read receipts, profile syncs, self-copies) with alert = 0, and those never
// count, which keeps this from producing phantom notifications.

package com.carrierpony.app.push

import android.content.Context
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.carrierpony.app.AppConfig
import com.carrierpony.app.crypto.PonyCryptoEngine
import com.carrierpony.app.identity.IdentityStore
import com.carrierpony.app.messaging.SealedKeyStore
import com.carrierpony.app.relay.RelayClient

class BackgroundCheckWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext
        // The live poll covers a visible app, and a working UnifiedPush endpoint
        // makes this redundant (the work is normally cancelled then anyway).
        if (AppVisibility.foreground || PushSupport.unifiedPushActive(app)) return Result.success()
        // Nothing could be shown, so don't spend battery or relay requests asking.
        if (!NotificationManagerCompat.from(app).areNotificationsEnabled()) return Result.success()

        val waiting = mutableSetOf<String>()
        val failed = mutableSetOf<String>()
        val identities = IdentityStore(app)
        for (fprHex in identities.list()) {
            val id = try { identities.load(fprHex) } catch (e: Exception) { null } ?: continue
            val crypto = PonyCryptoEngine(
                fingerprint = id.fingerprint,
                secretKey = id.secretKey,
                armored = id.armoredPublicKey,
                passphrase = { null },
                publicKeyResolver = { null }
            )
            val relay = RelayClient(
                baseURL = AppConfig.relayBaseURL(app),
                crypto = crypto,
                deviceID = AppConfig.deviceID(app)
            )
            // Sealed inbox: authenticated with the device key, so this works for
            // passphrase-protected accounts too. Skipped for an account whose
            // sealed device was never set up.
            SealedKeyStore(app, fprHex).existingDevice()?.let { (deviceId, deviceKey) ->
                try {
                    relay.sealedInbox(deviceId, deviceKey)
                        .filter { it.alert }
                        .forEach { waiting.add("s:$fprHex:${it.messageId}") }
                } catch (e: Exception) {
                    // Offline or relay error: try again next run.
                    failed.add(fprHex)
                }
            }
            // Fingerprint inbox: needs a signature, so only for accounts without a
            // passphrase (the same rule the app's background account poll follows).
            if (!id.protected) {
                try {
                    relay.inbox()
                        .filter { it.alert }
                        .forEach { waiting.add("l:$fprHex:${it.messageId}") }
                } catch (e: Exception) {
                    // Same as above.
                    failed.add(fprHex)
                }
            }
        }

        // Notify once per new batch. Ids the app has since received and
        // acknowledged drop out of the relay's answer, and out of this set.
        val prefs = app.getSharedPreferences("cp.backgroundCheck", Context.MODE_PRIVATE)
        val seen = prefs.getStringSet("seen", emptySet()) ?: emptySet()
        val fresh = waiting - seen
        // An account whose inbox failed this run keeps its old entries, or they
        // would drop out and be announced again next time. Accounts that answered
        // are pruned to what is still waiting.
        val keep = waiting + seen.filter { entry -> failed.any { entry.substringAfter(':').startsWith("$it:") } }
        prefs.edit().putStringSet("seen", HashSet(keep)).apply()
        if (fresh.isNotEmpty()) PushNotifier.postGenericNotification(app)
        return Result.success()
    }
}
