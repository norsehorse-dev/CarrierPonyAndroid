# CarrierPony — Play Store Listing Kit

Prepared from the Testers Community feedback report (items 1 & 2: screenshots + ASO).
Paste-ready for Play Console → Store presence → Main store listing.

---

## App title (30 chars max)

```
CarrierPony: PGP Chat & Files
```

(29 characters — keeps the strong "PGP" keyword in the title, unchanged from today.)

## Short description (80 chars max)

```
Private PGP messenger. End-to-end encrypted chat & file transfer. No account.
```

(77 characters. Leads with the three highest-value keywords: private, PGP, encrypted.)

## Full description (4000 chars max)

```
CarrierPony is a private messenger and secure file transfer app with real, end-to-end PGP encryption. No phone number. No email. No account. Just you, the people you trust, and encryption that actually works.

WHY CARRIERPONY?

Most "secure messaging" apps still ask for your phone number and store your contact list. CarrierPony asks for nothing. Your identity is an OpenPGP key pair created on your device — your private key never leaves it.

REAL PGP ENCRYPTION
• Every message and file is end-to-end encrypted with OpenPGP
• Messages are encrypted on your device and only decrypted on your contact's device
• The relay server forwards sealed envelopes only — no names, no contents, no plaintext
• Built on PGPonyCore, our open-source cryptography engine anyone can inspect

NO ACCOUNTS, NO TRACKING
• No phone number, email, or sign-up required
• No contact list upload, no ads, no analytics, no trackers
• A random per-install ID is all the relay ever sees

SECURE FILE TRANSFER
• Send photos, documents, and files of any type — encrypted end to end
• Files are never uploaded in readable form, and never scanned

PAIR WITH A QR CODE
• In person: one QR code scan pairs both phones
• Remote: send an invite over any channel you trust
• Verify contacts with a safety number, just like the pros do

STAY IN CONTROL
• App lock with fingerprint or face unlock
• Encrypted identity backups protected by a passphrase only you know
• Private, per-contact nicknames only you can see
• Reset and erase everything, any time

WHO IS IT FOR?

Anyone who wants private messaging without handing over their identity: journalists and sources, lawyers and clients, businesses with confidential files, families who simply believe private conversations should stay private.

CarrierPony is part of the Pony family of privacy apps, alongside PGPony (OpenPGP for your phone), AgePony, QuorumPony, and RelayPony.

Questions? support@carrierpony.com
Privacy policy and terms are available in the app under Settings → Legal.
```

(~1,900 characters — room to grow. Keyword coverage: private messenger, secure messaging,
PGP encryption, end-to-end encrypted, encrypted chat, secure file transfer, no account,
QR code pairing, OpenPGP, safety number.)

### Keyword strategy notes

Primary keywords (title + short description + first paragraph): **PGP, private messenger,
end-to-end encrypted, secure file transfer, no account**.
Secondary (body): encrypted chat, OpenPGP, QR pairing, safety number, app lock, open source.
Avoid: competitor names (policy violation), "military-grade" (flagged as spammy).

---

## Screenshot plan (item 1 of the feedback report)

8 phone screenshots, 1080×1920 or taller. Each = real app UI on a device frame,
short overlay headline on top. Suggested order:

| # | Screen to capture | Overlay text |
|---|-------------------|--------------|
| 1 | Conversation list with 3–4 chats | **Real PGP encryption. No account required.** |
| 2 | A conversation with sent/read receipts | **Messages only you two can read** |
| 3 | Files tab with received documents | **Send any file, end-to-end encrypted** |
| 4 | Pairing screen with QR code | **Pair with one QR scan** |
| 5 | Safety number verify screen | **Verify it's really them** |
| 6 | Onboarding "The relay knows nothing" page | **Our server sees only sealed envelopes** |
| 7 | App lock screen | **Locked with your fingerprint or face** |
| 8 | Settings with Backup/Legal visible | **Your keys, your backup, your rules** |

Production tips: use a demo identity with believable (non-real) names; capture in light
mode at 3x on a Pixel-class emulator (`adb exec-out screencap -p > 1.png`); keep overlay
text within the top 25% of the frame; reuse the app's accent color for the headline band
so the set looks branded. Localize overlays for de/es/fr/it/ja/pt/ru/zh later if
conversion in those markets matters — Play Console accepts per-locale screenshots.

Also worth adding: a feature graphic (1024×500) with the pony logo + "Real PGP encryption.
No accounts." — it's shown at the top of the listing on most devices.

---

## Release notes

### v1.1.0 (versionCode 2) — this release

```
• New: Privacy Policy and Terms of Service, in Settings → Legal
• New: pressing Back on the home screen now asks before closing the app
• On the Files tab, Back returns to Messages first
```

### v1.2.0 (versionCode 3) — next release

```
• New: "How Encryption Works" — a plain-language guide in Settings → Support
• New: Send Feedback from Settings, pre-filled and ready to go
```

---

## Play Console checklist (compliance items from the report)

1. Store presence → Main store listing: paste the descriptions above.
2. Store presence → Main store listing → Graphics: upload the 8 screenshots + feature graphic.
3. App content → Privacy policy: set the URL to your hosted copy of `privacy-policy.html`
   (see the file next to this document — host it at e.g. https://carrierpony.com/privacy).
4. App content → Data safety: with this app the honest answers are "no data collected,
   data is encrypted in transit, users can request deletion via reset" — plus Firebase
   Cloud Messaging push token under "App activity / other IDs" if prompted.
```

**Note:** the in-app legal pages and this kit use **support@carrierpony.com** as the
contact address — confirm that mailbox exists (or tell me the right one and I'll swap it
everywhere).
