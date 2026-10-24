// Demo.kt (debug source set)
// CarrierPony Android
//
// Store screenshots. tools/play-screenshots.sh launches the debug build with
//   --es demo_lang <en|de|es|fr|it|ja|pt|ru|zh>   seed demo data in that language
//   --es demo_screen <inbox|chat|group|files|pair> open that screen
// Seeding writes the app's own files (identity, contacts, conversations,
// groups, timers, attachments) for fictional contacts, so the screens show
// real UI over made-up data. Once seeded the app stays off the network
// (MainActivity skips startMessaging), so nothing reaches a relay.
//
// The release source set has a no-op Demo with the same API: none of this
// ships. Demo data uses fictional first names and example.com addresses.

package com.carrierpony.app.demo

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import com.carrierpony.app.attachments.AttachmentStore
import com.carrierpony.app.crypto.Fingerprint
import com.carrierpony.app.envelope.Threading
import com.carrierpony.app.identity.Identity
import com.carrierpony.app.identity.IdentityStore
import com.carrierpony.app.messaging.GroupMember
import com.carrierpony.app.messaging.membersToJson
import com.carrierpony.app.storage.AtRest
import com.carrierpony.core.CPGeneratedIdentity
import com.carrierpony.core.CPIdentityGenerator
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Calendar
import java.util.UUID

object Demo {

    private const val PREFS = "cp.prefs"
    private const val ACTIVE_KEY = "cp.demo.active"
    private const val CHAT_KEY = "cp.demo.chatPeer"
    private const val GROUP_KEY = "cp.demo.group"
    private const val GROUP_ID = "0D3E0D3E0D3E0D3E0D3E0D3E0D3E0D3E"

    /** True once demo data is installed. The app then stays off the network. */
    fun isActive(context: Context): Boolean =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(ACTIVE_KEY, false)

    /** Called from MainActivity.onCreate before AppModel exists. Seeds when the
     *  intent carries demo_lang, and returns the screen to open, if any. */
    fun prepare(context: Context, intent: Intent?): DemoStart? {
        val app = context.applicationContext
        intent?.getStringExtra("demo_lang")?.let { seed(app, it) }
        val screen = intent?.getStringExtra("demo_screen") ?: return null
        if (!isActive(app)) return null
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return DemoStart(screen, prefs.getString(CHAT_KEY, null), prefs.getString(GROUP_KEY, null))
    }

    // ── Seeding ────────────────────────────────────────────────────────

    private class Person(val name: String, val generated: CPGeneratedIdentity) {
        val fingerprint: Fingerprint = Fingerprint.from(generated.fingerprint)!!
        val hex: String get() = fingerprint.hex
    }

    private fun seed(app: Context, lang: String) {
        val t = DemoTexts.forLang(lang)
        val dir = app.filesDir
        AttachmentStore.directory = File(dir, "carrierpony-attachments")

        fun person(name: String, email: String) =
            Person(name, CPIdentityGenerator.generateV4Identity(name, email))
        val me = person(t.me, "me@example.com")
        val maya = person(t.maya, "maya@example.com")
        val leo = person(t.leo, "leo@example.org")
        val priya = person(t.priya, "priya@example.net")
        val jonas = person(t.jonas, "jonas@example.com")

        IdentityStore(app).save(Identity(
            fingerprint = me.fingerprint,
            secretKey = me.generated.secretKey,
            armoredPublicKey = me.generated.armoredPublicKey
        ))

        // Contacts.
        val contacts = JSONArray()
        for ((p, verified) in listOf(maya to true, leo to true, priya to false, jonas to false)) {
            contacts.put(JSONObject()
                .put("fingerprint", p.hex)
                .put("armored", p.generated.armoredPublicKey)
                .put("name", p.name)
                .put("trust", if (verified) "verified" else "unverified"))
        }
        write(File(dir, "carrierpony-contacts-${me.hex}.json"), contacts.toString())

        // Times line up with the demo status bar clock (09:41 today).
        val base = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 9); set(Calendar.MINUTE, 41); set(Calendar.SECOND, 0)
        }.timeInMillis / 1000
        val now = System.currentTimeMillis() / 1000
        val month = now + 30L * 86_400
        fun ago(minutes: Long) = base - minutes * 60

        val conversations = JSONArray()
        fun thread(peer: Person, messages: List<JSONObject>) {
            val arr = JSONArray(); messages.forEach { arr.put(it) }
            conversations.put(JSONObject()
                .put("threadID", Threading.pairwise(me.hex, peer.hex))
                .put("peer", peer.hex)
                .put("peerName", peer.name)
                .put("messages", arr))
        }
        fun msg(
            peer: Person, threadID: String, incoming: Boolean, text: String?, sentAt: Long,
            expiresAt: Long = month, read: Boolean = true, attachments: JSONArray = JSONArray()
        ): JSONObject {
            val m = JSONObject()
                .put("id", UUID.randomUUID().toString().uppercase())
                .put("threadID", threadID)
                .put("peer", peer.hex)
                .put("direction", if (incoming) "in" else "out")
                .put("attachments", attachments)
                .put("sentAt", sentAt)
                .put("expiresAt", expiresAt)
                .put("isRead", read || !incoming)
                .put("viaLan", false)
            if (text != null) m.put("text", text)
            return m
        }
        fun attachment(filename: String, mime: String, bytes: ByteArray): JSONArray =
            JSONArray().put(JSONObject()
                .put("id", UUID.randomUUID().toString())
                .put("filename", filename)
                .put("mime", mime)
                .put("size", bytes.size.toLong())
                .put("localPath", AttachmentStore.save(bytes, filename)))

        // Maya: disappearing timer on (1 day), so the header and the bubble
        // countdowns show it.
        val mayaThread = Threading.pairwise(me.hex, maya.hex)
        val day = now + 86_400
        thread(maya, listOf(
            msg(maya, mayaThread, true, t.maya1, ago(46), expiresAt = day - 46 * 60),
            msg(maya, mayaThread, false, t.maya2, ago(44), expiresAt = day - 44 * 60),
            msg(maya, mayaThread, true, t.maya3, ago(41), expiresAt = day - 41 * 60,
                attachments = attachment("trailhead.png", "image/png", scene(warm = false))),
            msg(maya, mayaThread, false, t.maya4, ago(39), expiresAt = day - 39 * 60),
            msg(maya, mayaThread, true, t.maya5, ago(37), expiresAt = day - 37 * 60, read = false)
        ))
        val leoThread = Threading.pairwise(me.hex, leo.hex)
        thread(leo, listOf(
            msg(leo, leoThread, true, t.leo1, ago(190),
                attachments = attachment("contract-draft.pdf", "application/pdf", pdf())),
            msg(leo, leoThread, false, t.leo2, ago(180))
        ))
        val priyaThread = Threading.pairwise(me.hex, priya.hex)
        thread(priya, listOf(
            msg(priya, priyaThread, false, null, ago(26 * 60),
                attachments = attachment("sunset.png", "image/png", scene(warm = true))),
            msg(priya, priyaThread, true, t.priya1, ago(26 * 60 - 4))
        ))
        val jonasThread = Threading.pairwise(me.hex, jonas.hex)
        thread(jonas, listOf(
            msg(jonas, jonasThread, true, t.jonas1, ago(2 * 24 * 60)),
            msg(jonas, jonasThread, false, t.jonas2, ago(2 * 24 * 60 - 3),
                attachments = attachment("notes.txt", "text/plain", t.jonas2.toByteArray()))
        ))

        // Group: me (admin) plus three contacts.
        val members = listOf(
            GroupMember(me.fingerprint, me.generated.armoredPublicKey, me.name, isAdmin = true),
            GroupMember(maya.fingerprint, maya.generated.armoredPublicKey, maya.name, isAdmin = false),
            GroupMember(leo.fingerprint, leo.generated.armoredPublicKey, leo.name, isAdmin = false),
            GroupMember(priya.fingerprint, priya.generated.armoredPublicKey, priya.name, isAdmin = false)
        )
        val groupMessages = JSONArray()
            .put(msg(leo, GROUP_ID, true, t.group1, ago(24)))
            .put(msg(priya, GROUP_ID, true, t.group2, ago(21)))
            .put(msg(me, GROUP_ID, false, t.group3, ago(18)))
            .put(msg(maya, GROUP_ID, true, t.group4, ago(12), read = false))
        val groups = JSONArray().put(JSONObject()
            .put("groupID", GROUP_ID)
            .put("name", t.group)
            .put("epoch", 1)
            .put("isChannel", false)
            .put("createdAt", ago(3 * 24 * 60))
            .put("members", membersToJson(members)))
        val snapshot = JSONObject()
            .put("conversations", conversations)
            .put("seen", JSONArray())
            .put("groups", groups)
            .put("groupThreads", JSONArray().put(JSONObject().put("groupID", GROUP_ID).put("messages", groupMessages)))
        write(File(dir, "carrierpony-conversations-${me.hex}.json"), snapshot.toString())
        write(File(dir, "carrierpony-timers-${me.hex}.json"), JSONObject().put(mayaThread, 86_400L).toString())

        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("cp.lang", lang)
            .putBoolean("cp.onboarded", true)
            .putBoolean("cp.askedNotifications", true)
            .putBoolean("cp.notifHintDismissed", true)
            .putBoolean("cp.appLock.enabled", false)
            .putString("cp.profile.${me.hex}", me.name)
            .putBoolean(ACTIVE_KEY, true)
            .putString(CHAT_KEY, maya.hex)
            .putString(GROUP_KEY, GROUP_ID)
            .commit()
    }

    private fun write(file: File, text: String) {
        file.parentFile?.mkdirs()
        AtRest.writeText(file, text)
    }

    /** A simple generated landscape (no stock photos): sky, sun, hills. */
    private fun scene(warm: Boolean): ByteArray {
        val w = 1200; val h = 900
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val sky = Paint().apply {
            shader = if (warm) LinearGradient(0f, 0f, 0f, h.toFloat(), 0xFFF6B26B.toInt(), 0xFFE06C75.toInt(), Shader.TileMode.CLAMP)
                     else LinearGradient(0f, 0f, 0f, h.toFloat(), 0xFF8EC5FC.toInt(), 0xFFE0F2FE.toInt(), Shader.TileMode.CLAMP)
        }
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), sky)
        c.drawCircle(if (warm) 820f else 300f, if (warm) 520f else 220f, 90f,
            Paint(Paint.ANTI_ALIAS_FLAG).apply { color = if (warm) 0xFFFFE08A.toInt() else 0xFFFFF7D6.toInt() })
        fun hill(color: Int, peaks: List<Pair<Float, Float>>) {
            val p = Path().apply {
                moveTo(0f, h.toFloat())
                peaks.forEach { (x, y) -> lineTo(x, y) }
                lineTo(w.toFloat(), h.toFloat()); close()
            }
            c.drawPath(p, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color })
        }
        if (warm) {
            hill(0xFF9B4F6B.toInt(), listOf(0f to 640f, 260f to 520f, 520f to 610f, 800f to 500f, 1200f to 620f))
            hill(0xFF5E3554.toInt(), listOf(0f to 760f, 340f to 680f, 700f to 760f, 1000f to 690f, 1200f to 740f))
        } else {
            hill(0xFF7FA7C9.toInt(), listOf(0f to 560f, 300f to 330f, 520f to 480f, 760f to 280f, 1200f to 520f))
            hill(0xFF3F6E5A.toInt(), listOf(0f to 760f, 280f to 600f, 600f to 720f, 900f to 590f, 1200f to 700f))
        }
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
        bmp.recycle()
        return out.toByteArray()
    }

    /** A one-page PDF so the Files tab shows a real document. */
    private fun pdf(): ByteArray =
        ("%PDF-1.4\n1 0 obj<</Type/Catalog/Pages 2 0 R>>endobj\n" +
            "2 0 obj<</Type/Pages/Kids[3 0 R]/Count 1>>endobj\n" +
            "3 0 obj<</Type/Page/Parent 2 0 R/MediaBox[0 0 612 792]>>endobj\n" +
            "% " + "0".repeat(77) + "\n").repeat(1).let { head ->
                val filler = "% " + "0".repeat(77) + "\n"
                val b = StringBuilder(head)
                while (b.length < 238_000) b.append(filler)
                b.append("trailer<</Root 1 0 R>>\n%%EOF\n").toString()
            }.toByteArray()
}
