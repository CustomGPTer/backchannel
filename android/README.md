# Backchannel for Android

Private messages, phone to phone. No server.

- **End-to-end encrypted** with the Signal Protocol (Signal's own `libsignal` library,
  including its post-quantum key exchange).
- **No server.** Each phone runs Tor and has its own onion address. Messages go straight
  from one phone to the other across the Tor network. Nothing is stored in between.
- **Encrypted on the phone.** Everything the app keeps is in a SQLCipher database. Its key
  is locked with the user's PIN or passphrase (Argon2id) and with a key held in the phone's
  secure hardware.
- **Android 10 and newer.**

Version 0.3 has:

- setup and lock, encrypted storage, Tor, adding a contact by QR code or pasted invite,
  text messages both ways with on-phone queueing;
- photos (shrunk, and stripped of location and all other hidden details before sending)
  and voice notes, stored only inside the encrypted database;
- disappearing messages with a timer per chat, shared by both phones;
- a contact's name and picture copied from one address-book entry the user chooses, with
  no permission to read the address book;
- a choice of lock (PIN or passphrase) with the strength of each shown, fingerprint for
  opening the screens, auto-lock time, and changing the PIN;
- a duress PIN that wipes everything;
- an encrypted backup file, and restoring it on a new phone;
- a check for newer versions, made over Tor.

## How it works

1. **Adding someone.** One person shows a code (QR or text). It holds their onion address,
   their public identity key and a single-use token. The other phone connects to that
   address over Tor, presents the token, receives a fresh set of public keys, checks they
   belong to the identity in the code, and starts a Signal session.
2. **Sending.** A message is saved first, then encrypted and sent when the other phone can
   be reached. It is marked delivered only when the other phone confirms it. If the other
   phone is off, the message waits and is retried.
3. **Receiving.** The phone only acts on a connection that carries a valid invite token or
   a message that decrypts under an existing contact's keys. Everything else is dropped.
4. **Photos and voice notes** travel inside the same encrypted message as text does (up to
   1 MB), so there is nowhere else for them to be stored or intercepted.
5. **Disappearing messages.** Each message carries the timer that was set when it was
   written. The sender's copy starts counting when it is delivered; the receiver's when it
   has been seen. A message still waiting to be sent never expires.

6. **The duress PIN.** The vault file always holds 32 bytes where a duress PIN's
   fingerprint goes: random ones if none is set, so the file gives no sign either way.
   Typing the duress PIN deletes the hardware key first (which alone makes the database
   unreadable for good), then overwrites and removes the files.
7. **Fingerprint** only opens the screens while the app is already running. It is never
   part of the key. After a restart the PIN or passphrase is always needed.
8. **Backup.** Every row of the database is written into one file, encrypted with
   AES-256-GCM under a key made from the backup passphrase with Argon2id. On restore the
   Signal sessions in the file are thrown away, because they go stale as soon as another
   message is exchanged; the phone then proves who it is to each contact with its identity
   key and both sides set up a fresh session by themselves.
9. **Updates.** The app asks GitHub's public API, through Tor, for the newest release
   number and says if there is one. Installing is left to Android, which refuses any
   update not signed with the same key.

A photo being taken and a voice note being recorded exist briefly as a file in the app's
private scratch folder, because Android's camera and recorder need a file to write to. The
file is read into the encrypted database, overwritten and deleted straight away.

## Layout

| Path | What it is |
| --- | --- |
| `app/src/main/java/uk/jj/backchannel/core` | The messaging engine. No Android or Tor code, so it runs in tests on a computer. |
| `.../data` | The database (SQLCipher on the phone). |
| `.../vault` | The key vault: PIN hashing, hardware key, wrong-PIN delays. |
| `.../net` | Starting and watching Tor. |
| `.../media` | Preparing photos, recording and playing voice notes. |
| `.../ui` | Screens (Jetpack Compose). |
| `app/src/test` | Tests. |

## Building

Needs JDK 17 or newer and the Android SDK (platform 36, build-tools 36.0.0, NDK
27.0.12077973). Android Studio installs all of these.

1. Put the SDK path in `local.properties`: `sdk.dir=/path/to/Android/sdk`
2. Run `./gradlew :app:testDebugUnitTest :app:assembleRelease`
3. The APK is `app/build/outputs/apk/release/app-release.apk`

A signed release build needs your own signing key. Put a keystore in a `keys` folder here with a
`keys/signing.properties` file holding `storeFile`, `storePassword`, `keyAlias` and `keyPassword`.
Without that file the release APK is built unsigned. The `keys` folder is in `.gitignore` and must
never be published.

## Tests

`./gradlew :app:testDebugUnitTest` runs:

- two or more complete messaging engines talking to each other (adding, messaging both
  ways, queueing while one is off, restart, single-use and expiring codes, forged codes,
  strangers and impostors being refused, nothing readable on the wire);
- photos, voice notes, shared disappearing timers and their countdown rules;
- photo preparation (shrinking, turning upright, removing location and camera details);
- the key vault (wrong PIN, tampering, another phone's hardware key, duress PIN, changing PIN);
- the app's own flows end to end: setup, unlock, duress wipe, backup, restore;
- the backup file format (wrong passphrase, changed, cut-short and rearranged files);
- recovering by itself after an older backup is restored;
- the database code, including upgrading a version 0.1 database in place;
- the real Tor program starting and keeping a stable onion address (Linux only);
- every screen drawn and saved as a picture in `app/build/shots`.

The tests cannot send a message across the real Tor network. That needs two phones.

## Licence

AGPL-3.0 (see `LICENSE` in the folder above), as required by `libsignal`. Tor, lyrebird and the onion wrapper
come from the Tor Project and the Briar Project (BSD-style licences); SQLCipher is from
Zetetic (BSD-style); Bouncy Castle (MIT); ZXing (Apache-2.0).
