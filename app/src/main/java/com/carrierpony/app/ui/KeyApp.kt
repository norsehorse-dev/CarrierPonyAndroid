// Picking a contact's public key from a key app on the phone (PGPony,
// OpenKeychain, or anything else that speaks the OpenPGP API). The key app
// shows its own picker and hands back one public key. That key then goes
// through the same check as a pasted one, and nothing is added or sent until
// the person taps Add contact.
//
// Only two calls are made: GET_KEY_IDS, to let the person choose, and GET_KEY,
// to read the chosen public key. No private key operation is ever asked for.

package com.carrierpony.app.ui

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.ParcelFileDescriptor
import androidx.core.content.IntentCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.openintents.openpgp.IOpenPgpService2
import kotlin.coroutines.resume

/** A key app installed on the phone. */
data class KeyApp(val packageName: String, val label: String)

object KeyApps {

    private const val SERVICE = "org.openintents.openpgp.IOpenPgpService2"
    private const val ACTION_GET_KEY_IDS = "org.openintents.openpgp.action.GET_KEY_IDS"
    private const val ACTION_GET_KEY = "org.openintents.openpgp.action.GET_KEY"
    private const val EXTRA_API_VERSION = "api_version"
    private const val EXTRA_USER_IDS = "user_ids"
    private const val EXTRA_KEY_ID = "key_id"
    private const val EXTRA_ASCII_ARMOR = "ascii_armor"
    private const val RESULT_CODE = "result_code"
    private const val RESULT_INTENT = "intent"
    private const val RESULT_KEY_IDS = "key_ids"
    private const val CODE_SUCCESS = 1
    private const val CODE_USER_INTERACTION = 2
    private const val API_VERSION = 11

    sealed class Outcome {
        /** The public key the person chose, as the key app wrote it. */
        class Key(val bytes: ByteArray) : Outcome()

        /** The key app has to show a screen first (its access prompt or its
         *  key picker). Launch [intent], then call [fetch] again with what
         *  comes back. */
        class NeedsUser(val intent: PendingIntent) : Outcome()

        /** The person closed the picker without choosing a key. */
        data object NothingChosen : Outcome()

        data object Failed : Outcome()
    }

    /** Key apps on this phone, by name. */
    fun installed(context: Context): List<KeyApp> {
        val pm = context.packageManager
        return pm.queryIntentServices(Intent(SERVICE), 0)
            .mapNotNull { it.serviceInfo }
            .filter { it.packageName != context.packageName }
            .map { KeyApp(it.packageName, it.applicationInfo.loadLabel(pm).toString()) }
            .distinctBy { it.packageName }
            .sortedBy { it.label.lowercase() }
    }

    /** The request that opens the key app's picker. */
    fun pickRequest(): Intent = Intent(ACTION_GET_KEY_IDS)
        .putExtra(EXTRA_API_VERSION, API_VERSION)
        .putExtra(EXTRA_USER_IDS, arrayOf<String>())

    /** Of the keys picked, the one to use. A contact has one key, so only the
     *  first is taken. */
    fun firstKeyId(ids: LongArray?): Long? = ids?.firstOrNull { it != 0L }

    /** Run [request] against the key app. Start with [pickRequest]; after a
     *  [Outcome.NeedsUser] screen returns, pass the intent it returned. */
    suspend fun fetch(context: Context, packageName: String, request: Intent): Outcome =
        withContext(Dispatchers.IO) {
            try {
                withService<Outcome>(context, packageName) { service ->
                    val picked = execute(context, service, request, wantOutput = false).first
                    when (picked.getIntExtra(RESULT_CODE, 0)) {
                        CODE_USER_INTERACTION -> pending(picked)
                        CODE_SUCCESS -> {
                            val id = firstKeyId(picked.getLongArrayExtra(RESULT_KEY_IDS))
                                ?: return@withService Outcome.NothingChosen
                            val get = Intent(ACTION_GET_KEY)
                                .putExtra(EXTRA_API_VERSION, API_VERSION)
                                .putExtra(EXTRA_KEY_ID, id)
                                .putExtra(EXTRA_ASCII_ARMOR, true)
                            val (result, bytes) = execute(context, service, get, wantOutput = true)
                            if (result.getIntExtra(RESULT_CODE, 0) == CODE_SUCCESS && bytes.isNotEmpty()) {
                                Outcome.Key(bytes)
                            } else {
                                Outcome.Failed
                            }
                        }
                        else -> Outcome.Failed
                    }
                } ?: Outcome.Failed
            } catch (e: Exception) {
                Outcome.Failed
            }
        }

    private fun pending(result: Intent): Outcome {
        val intent = IntentCompat.getParcelableExtra(result, RESULT_INTENT, PendingIntent::class.java)
        return if (intent != null) Outcome.NeedsUser(intent) else Outcome.Failed
    }

    /** One API call. With [wantOutput] the key app writes into a pipe, read
     *  here up to the size limit for a key. */
    private fun execute(
        context: Context,
        service: IOpenPgpService2,
        request: Intent,
        wantOutput: Boolean
    ): Pair<Intent, ByteArray> {
        var bytes = ByteArray(0)
        var pump: Thread? = null
        val pipeId = (System.nanoTime() and 0x7fffffff).toInt()
        if (wantOutput) {
            val readSide: ParcelFileDescriptor = service.createOutputPipe(pipeId)
            pump = kotlin.concurrent.thread {
                try {
                    ParcelFileDescriptor.AutoCloseInputStream(readSide).use { bytes = IncomingKey.readCapped(it) }
                } catch (e: Exception) {
                    bytes = ByteArray(0)
                }
            }
        }
        val result = service.execute(request, null, pipeId) ?: Intent()
        pump?.join(10_000)
        // The answer can carry the key app's own error object. Its class is
        // in this app under the same name, so the extras can be read.
        result.setExtrasClassLoader(context.classLoader)
        return result to bytes
    }

    private suspend fun <T> withService(
        context: Context,
        packageName: String,
        block: (IOpenPgpService2) -> T
    ): T? {
        val app = context.applicationContext
        var connection: ServiceConnection? = null
        try {
            val service = withTimeoutOrNull(8_000) {
                suspendCancellableCoroutine<IOpenPgpService2?> { continuation ->
                    val made = object : ServiceConnection {
                        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                            if (continuation.isActive) continuation.resume(IOpenPgpService2.Stub.asInterface(binder))
                        }
                        override fun onServiceDisconnected(name: ComponentName?) {}
                    }
                    connection = made
                    val bound = app.bindService(
                        Intent(SERVICE).setPackage(packageName), made, Context.BIND_AUTO_CREATE
                    )
                    if (!bound && continuation.isActive) continuation.resume(null)
                }
            } ?: return null
            return block(service)
        } finally {
            connection?.let { try { app.unbindService(it) } catch (e: Exception) {} }
        }
    }
}
