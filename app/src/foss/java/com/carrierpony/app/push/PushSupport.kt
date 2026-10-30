// PushSupport.kt
// CarrierPony Android — FOSS FLAVOR
//
// The F-Droid / GitHub build carries no Firebase and no Google Play Services.
// It hears about new messages two ways:
//
// - UnifiedPush, when the user has a distributor installed (Sunup, ntfy,
//   NextPush, ...). The endpoint and its Web Push keys go to the CarrierPony
//   push gateway exactly where the Play build's FCM token goes, and the gateway
//   POSTs an encrypted, content-free wake to it.
// - A periodic background check (BackgroundCheckWorker) when there is no
//   distributor, or the user chose not to use one. Slower, but nothing to install.
//
// Same API as the play flavor's PushSupport, so AppModel stays flavor-agnostic.

package com.carrierpony.app.push

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import org.unifiedpush.android.connector.UnifiedPush
import java.util.concurrent.TimeUnit

object PushSupport {
    /** True: UnifiedPush when a distributor is installed, otherwise the
     *  background check stands in. */
    const val available = true

    /** True: the user can pick a UnifiedPush distributor or none. */
    const val supportsChoice = true

    private const val PREFS = "cp.unifiedpush"
    private const val KEY_ENDPOINT = "endpoint"
    private const val KEY_P256DH = "p256dh"
    private const val KEY_AUTH = "auth"
    private const val KEY_OPTED_OUT = "optedOut"
    private const val KEY_GATEWAY_USABLE = "gatewayUsable"
    // The endpoint the push gateway last accepted. Only then is UnifiedPush
    // counted as working; until then the background check keeps running.
    private const val KEY_ACCEPTED = "acceptedEndpoint"
    // Set while the gateway holds a UnifiedPush registration for this install,
    // whichever endpoint it points at. Cleanup keys off this, not KEY_ACCEPTED.
    private const val KEY_GATEWAY_ROW = "gatewayRow"
    // Set when the endpoint is dropped while the gateway still has a row, so
    // AppModel removes it (now, or on the next start if the app wasn't running).
    // Cleared only once the gateway confirmed the removal.
    private const val KEY_PENDING_CLEAR = "pendingClear"
    private const val WORK_NAME = "cp.backgroundCheck"

    // Installed by AppModel. The UnifiedPush service can start without an
    // AppModel (process woken by the distributor); then these stay no-ops and the
    // stored endpoint is submitted on the next app start instead.
    @Volatile
    internal var onNewToken: (PushRegistration) -> Unit = {}

    @Volatile
    internal var onWake: () -> Boolean = { false }

    @Volatile
    internal var onCleared: () -> Unit = {}

    fun ensureChannel(context: Context) = PushNotifier.ensureChannel(context)

    fun install(onNewToken: (PushRegistration) -> Unit, onWake: () -> Boolean, onCleared: () -> Unit = {}) {
        this.onNewToken = onNewToken
        this.onWake = onWake
        this.onCleared = onCleared
    }

    /** AppModel calls this after the gateway accepted a registration. From then on
     *  UnifiedPush counts as working and the background check stops. */
    fun markGatewayRegistered(context: Context, reg: PushRegistration) {
        if (reg.platform != "unifiedpush") return
        val app = context.applicationContext
        val p = prefs(app)
        if (p.getString(KEY_ENDPOINT, null) != reg.token) return
        p.edit().putString(KEY_ACCEPTED, reg.token).putBoolean(KEY_GATEWAY_ROW, true).remove(KEY_PENDING_CLEAR).apply()
        scheduleOrCancel(app)
    }

    /** True when the endpoint was dropped (the user chose no distributor, or the
     *  distributor unregistered us) while the gateway still has a registration
     *  that should be removed so nothing keeps pushing to the old endpoint. */
    fun pendingClear(context: Context): Boolean =
        prefs(context.applicationContext).getBoolean(KEY_PENDING_CLEAR, false)

    /** AppModel calls this once the gateway confirmed the removal. */
    fun commitClear(context: Context) {
        prefs(context.applicationContext).edit().remove(KEY_PENDING_CLEAR).remove(KEY_GATEWAY_ROW).apply()
    }

    /** Hand back the stored UnifiedPush endpoint, if there is one. A new or
     *  changed endpoint also arrives through onNewToken from the service. */
    fun fetchToken(context: Context, onToken: (PushRegistration) -> Unit) {
        storedRegistration(context)?.let(onToken)
    }

    /** Called on every foreground. Keeps the UnifiedPush registration current,
     *  picks the distributor automatically when exactly one is installed, and
     *  schedules or cancels the background check to match. gatewayUsable is false
     *  on a custom relay without the gateway opt-in, where a UnifiedPush endpoint
     *  would never be woken. */
    fun refresh(context: Context, gatewayUsable: Boolean) {
        val app = context.applicationContext
        val edit = prefs(app).edit().putBoolean(KEY_GATEWAY_USABLE, gatewayUsable)
        // With the gateway unusable (custom relay, opt-in off) any earlier
        // acceptance is void; it has to be accepted again once it's back.
        if (!gatewayUsable) edit.remove(KEY_ACCEPTED)
        edit.apply()
        if (!optedOut(app)) {
            var distributor = UnifiedPush.getAckDistributor(app) ?: UnifiedPush.getSavedDistributor(app)
            if (distributor == null) {
                val installed = UnifiedPush.getDistributors(app)
                if (installed.size == 1) {
                    UnifiedPush.saveDistributor(app, installed[0])
                    distributor = installed[0]
                }
            }
            if (distributor != null) {
                // Cheap to repeat: the distributor answers with the same endpoint,
                // or a new one if it changed while we weren't running.
                try {
                    UnifiedPush.register(app)
                } catch (e: Exception) {
                    // The service hears about failures; the background check covers it.
                }
            } else if (storedRegistration(app) != null) {
                // The distributor was uninstalled: its endpoint is dead.
                clearEndpoint(app, gatewayCleanup = true)
            }
        }
        scheduleOrCancel(app)
    }

    fun mode(context: Context): PushMode =
        if (unifiedPushActive(context.applicationContext)) PushMode.UNIFIED_PUSH else PushMode.BACKGROUND_CHECK

    fun distributors(context: Context): List<PushDistributor> {
        val app = context.applicationContext
        return UnifiedPush.getDistributors(app).map { PushDistributor(it, label(app, it)) }
    }

    /** The distributor in use, or null for none / background check only. */
    fun currentDistributor(context: Context): String? {
        val app = context.applicationContext
        if (optedOut(app)) return null
        return UnifiedPush.getAckDistributor(app) ?: UnifiedPush.getSavedDistributor(app)
    }

    fun useDistributor(context: Context, packageName: String) {
        val app = context.applicationContext
        prefs(app).edit().putBoolean(KEY_OPTED_OUT, false).apply()
        // Switching distributors: the new endpoint replaces the old one on the same
        // gateway registration, so there is nothing to remove there. The old
        // distributor is told to drop its registration.
        val previous = UnifiedPush.getSavedDistributor(app)
        if (previous != null && previous != packageName) {
            try {
                UnifiedPush.unregister(app)
            } catch (e: Exception) {
                // Already gone.
            }
        }
        if (previous != packageName) clearEndpoint(app, gatewayCleanup = false)
        UnifiedPush.saveDistributor(app, packageName)
        try {
            UnifiedPush.register(app)
        } catch (e: Exception) {
            // See refresh().
        }
        scheduleOrCancel(app)
    }

    fun useBackgroundCheck(context: Context) {
        val app = context.applicationContext
        prefs(app).edit().putBoolean(KEY_OPTED_OUT, true).apply()
        try {
            UnifiedPush.unregister(app)
            UnifiedPush.removeDistributor(app)
        } catch (e: Exception) {
            // Already gone.
        }
        clearEndpoint(app, gatewayCleanup = true)
        scheduleOrCancel(app)
    }

    // ── Endpoint storage (used by UpPushService) ────────────────────────

    /** True when this endpoint differs from what is stored, or was never accepted
     *  by the gateway, so it has to be submitted. */
    internal fun needsSubmit(context: Context, url: String, p256dh: String?, auth: String?): Boolean {
        val p = prefs(context.applicationContext)
        return p.getString(KEY_ENDPOINT, null) != url ||
            p.getString(KEY_P256DH, null) != p256dh ||
            p.getString(KEY_AUTH, null) != auth ||
            p.getString(KEY_ACCEPTED, null) != url
    }

    internal fun saveEndpoint(context: Context, url: String, p256dh: String?, auth: String?) {
        val app = context.applicationContext
        val p = prefs(app)
        val changed = p.getString(KEY_ENDPOINT, null) != url ||
            p.getString(KEY_P256DH, null) != p256dh ||
            p.getString(KEY_AUTH, null) != auth
        val edit = p.edit()
            .putString(KEY_ENDPOINT, url)
            .putString(KEY_P256DH, p256dh)
            .putString(KEY_AUTH, auth)
        if (changed) {
            // A new URL or new keys must be accepted again before the background
            // check stops. Any pending removal is moot: the new registration
            // overwrites the same gateway row.
            edit.remove(KEY_ACCEPTED).remove(KEY_PENDING_CLEAR)
        }
        edit.apply()
        scheduleOrCancel(app)
    }

    /** Forget the endpoint. With gatewayCleanup, and a gateway registration in
     *  place, that registration is removed too (via AppModel). */
    internal fun clearEndpoint(context: Context, gatewayCleanup: Boolean) {
        val app = context.applicationContext
        val p = prefs(app)
        val hasRow = p.getBoolean(KEY_GATEWAY_ROW, false)
        val edit = p.edit().remove(KEY_ENDPOINT).remove(KEY_P256DH).remove(KEY_AUTH).remove(KEY_ACCEPTED)
        if (gatewayCleanup && hasRow) edit.putBoolean(KEY_PENDING_CLEAR, true)
        edit.apply()
        scheduleOrCancel(app)
        if (gatewayCleanup && hasRow) {
            try {
                onCleared()
            } catch (e: Exception) {
                // Picked up on the next app start through pendingClear.
            }
        }
    }

    internal fun storedRegistration(context: Context): PushRegistration? {
        val p = prefs(context.applicationContext)
        val url = p.getString(KEY_ENDPOINT, null) ?: return null
        return PushRegistration(url, "unifiedpush", p.getString(KEY_P256DH, null), p.getString(KEY_AUTH, null))
    }

    /** True when the endpoint was registered with Web Push keys, so every genuine
     *  wake arrives encrypted and an unencrypted one can be ignored. */
    internal fun expectsEncrypted(context: Context): Boolean =
        prefs(context.applicationContext).getString(KEY_P256DH, null) != null

    internal fun unifiedPushActive(context: Context): Boolean {
        val p = prefs(context)
        val endpoint = p.getString(KEY_ENDPOINT, null) ?: return false
        return !p.getBoolean(KEY_OPTED_OUT, false) &&
            p.getBoolean(KEY_GATEWAY_USABLE, true) &&
            p.getString(KEY_ACCEPTED, null) == endpoint
    }

    // ── Background check scheduling ─────────────────────────────────────

    private fun scheduleOrCancel(app: Context) {
        val work = WorkManager.getInstance(app)
        if (unifiedPushActive(app)) {
            work.cancelUniqueWork(WORK_NAME)
        } else {
            val request = PeriodicWorkRequestBuilder<BackgroundCheckWorker>(15, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            work.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }

    private fun optedOut(app: Context) = prefs(app).getBoolean(KEY_OPTED_OUT, false)

    private fun prefs(app: Context) = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun label(app: Context, packageName: String): String = try {
        val pm = app.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
    } catch (e: Exception) {
        packageName
    }
}
