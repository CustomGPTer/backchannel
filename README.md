# Backchannel

Backchannel is an Android messenger with no server. Every message is encrypted on your phone with the
Signal Protocol and goes straight to the other person's phone across the Tor network. Nothing is stored
anywhere in between. It has no adverts, no accounts, no phone numbers and no paid version.

- Website: https://customgpter.github.io/backchannel/
- Latest app file: https://github.com/CustomGPTer/backchannel/releases/latest
- Privacy: https://customgpter.github.io/backchannel/privacy.html

## Read this before you rely on it

- **Both phones have to be online.** There is no server to hold a message. It waits on your phone, marked
  Waiting, until the other phone is switched on, connected and running Backchannel.
- **Let it run in the background** when it asks, or Android will put it to sleep and nothing will arrive.
- **Unlock it once after a restart** or an update. Until then nothing can be received.
- **Nobody can reset your PIN or passphrase.** There is no account and no recovery code.
- **Make a backup file** (Settings > Backup). Uninstalling the app, clearing its storage or losing the phone
  deletes everything. A backup must only ever be running on one phone.
- **Add people in person when you can.** Scanning a code on their screen is safer than an invite sent as text.

## What it does

- One-to-one text, photos and voice notes, encrypted end to end with libsignal, including its post-quantum
  key exchange
- No server: each phone runs Tor and has its own onion address, and messages go directly between phones
- Adding a contact by scanning a code or pasting an invite; each code adds one person and then stops working
- Messages are saved first and sent when the other phone can be reached, and marked delivered only when it
  confirms them
- Photos are shrunk and stripped of location and every other hidden detail before sending
- Disappearing messages with a timer per chat, shared by both phones
- An encrypted database (SQLCipher) whose key is locked with your PIN or passphrase (Argon2id) and again
  with the phone's security hardware
- A PIN or passphrase with its strength shown, optional fingerprint, growing waits after wrong tries
- A duress PIN that wipes everything
- One encrypted backup file with its own passphrase
- Notifications that say only "New message"; screenshots blocked
- A check for newer versions, made over Tor

## What it cannot do

Backchannel cannot protect you from spy software on your own phone, from someone holding your unlocked
phone, or from the person you are talking to. It does not hide the fact that you are using Tor. It is at
an early version and has not had an independent security review. The source is here so that anyone can
check it.

## Building

The app is in the `android` folder. See `android/README.md`, which also explains how it works.

## Licence

© 2026 Jonathan Jackson. Published under the GNU Affero General Public License, version 3 (see `LICENSE`),
the licence of the Signal library it is built on.
