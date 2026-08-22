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
