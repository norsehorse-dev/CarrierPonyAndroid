# CarrierPony release rules.
#
# BouncyCastle: the crypto core reaches BC through its lightweight API with
# compile-time references, so R8 keeps what it sees — but BC also does
# internal reflection (digest/cipher registries) that shrinking can sever
# in ways that only surface at runtime, on some devices, as failed
# decrypts. For a crypto app, correctness beats the shrink savings on this
# one library: keep it whole. Everything else still minifies.
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**

# javax.naming is referenced by BC's X.509 paths we never use.
-dontwarn javax.naming.**

# ── foss build: WorkManager and UnifiedPush (added 3.3.1) ─────────────────
# 3.3.0 crashed on launch in release builds: WorkManager's startup initializer
# creates its Room database by reflection (WorkDatabase_Impl.<init>()), and R8
# had removed that constructor. Keep everything these libraries instantiate by
# name, rather than relying on their consumer rules alone.
-keep class * extends androidx.room.RoomDatabase { <init>(); }
-keep class * extends androidx.work.ListenableWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}
-keep class * extends androidx.work.InputMerger { public <init>(); }

# UnifiedPush connector: endpoints and messages arrive as Parcelables from the
# distributor's process, and the app's PushService is bound by name.
-keep class org.unifiedpush.android.connector.** { *; }
-keep class * extends org.unifiedpush.android.connector.PushService { *; }

# Tink, which the connector uses to generate the Web Push keys and decrypt
# wakes. Its key managers and primitives are looked up by type at runtime.
-keep class com.google.crypto.tink.** { *; }
-dontwarn com.google.crypto.tink.**
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**
