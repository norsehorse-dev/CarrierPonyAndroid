# CarrierPony for Android

CarrierPony is a private messenger and secure file transfer app built on real OpenPGP
encryption. There is no phone number, no email, and no account. Your identity is an OpenPGP
key pair created on your device, and your private key never leaves it.

This repository holds the Android client. The app is also available on Google Play.

## How it works

Every message and every file is end-to-end encrypted with OpenPGP. Content is encrypted on
your device and only decrypted on your contact's device. Between the two, a store-and-forward
relay carries sealed envelopes: it sees a recipient fingerprint, an opaque message id, a
size, and timestamps, and nothing else. No names, no contents, no plaintext.

The relay is a separate, self-hostable component under its own Apache-2.0 license. The client
authenticates to it with a challenge-response signature from your key, and a random
per-install id is the only device identifier it ever holds.

Pairing happens two ways. In person, one QR scan pairs both phones and marks the contact
verified, because the fingerprint travelled physically. Remotely, you send an invite over a
channel you already trust, and the invitee's app fetches your key from the relay and refuses
to add it unless it hashes to the fingerprint the invite carried. Either way you can compare a
safety number to be certain no one is in the middle.

## Building

A debug build needs no signing setup:

```
git clone https://github.com/norsehorse-dev/CarrierPonyAndroid.git
cd CarrierPonyAndroid
./gradlew assembleDebug
```

Requirements: JDK 17, the Android SDK with API 36, and `ANDROID_HOME` (or a `local.properties`
with `sdk.dir`) pointing at it. The Gradle wrapper fetches the rest.

Release builds are signed from a `keystore.properties` at the repo root, which stays out of
version control. If it is absent, the release variant simply builds unsigned. The file names a
keystore and its credentials:

```
storeFile=carrierpony-release.jks
storePassword=...
keyAlias=...
keyPassword=...
```

Push notifications use Firebase Cloud Messaging and stay dormant until a `google-services.json`
is present. Without it the app still delivers messages by polling while it is open; the push
pipe only ever carries a contentless wake signal, never message content.

## Layout

- `app/` is the Android application: the UI (Jetpack Compose), the messaging and pairing
  stores, the relay client, and the identity and lock handling.
- `carrierponycore/` is the cryptography and envelope layer the app codes against.
- The envelope format and the pairwise thread derivation are byte-compatible with the iOS
  client, so the two platforms open each other's messages and restore each other's backups.

## Privacy

No phone number, no email, no sign-up. No contact-list upload, no ads, no analytics, no
trackers. Identity backups are sealed with a passphrase only you hold, and there is no
passphrase recovery: a lost passphrase means a lost backup, by design. The privacy policy and
terms ship inside the app under Settings, Legal.

## License

Licensed under the Apache License, Version 2.0. See [LICENSE](LICENSE).

Copyright 2026 The CarrierPony Authors.
