# Open WebDAV Messenger

End-to-end encrypted messenger for Android. No server — your own WebDAV cloud disk (Yandex.Disk, Nextcloud, any WebDAV share) is the transport.

**What it does, for whom, and current limits:** [`docs/product.md`](docs/product.md)

---

## How it works

1. **One person creates a community** — the app generates keys and an invite (QR code + string).
2. **Others join by scanning the QR** — the invite carries disk access + encryption keys.
3. **Messages are encrypted client-side** — the disk operator sees only random-looking files.
4. **The app polls the disk** — new messages arrive in the background, notifications appear.

No sign-up. No phone number. No server. Just a folder on a disk you already control.

## Features

- **End-to-end encrypted** — XChaCha20-Poly1305 AEAD, Ed25519 signatures, Argon2id key derivation
- **Community chat** — all members in one shared encrypted chat
- **Direct messages** — private 1-on-1 chats with per-pair X25519 DH keys
- **Member directory** — signed, community-key-encrypted entries with display names
- **Read receipts** — per-message delivery tracking
- **Font size** — adjustable 0.8×–1.5× in settings
- **Dark/light theme** — manual or follow system
- **Notifications** — Android notification on new messages
- **Retention window** — auto-delete old messages from disk (host-configurable, 7–90 days)
- **Member removal** — host rotates disk credential, remaining members auto-update
- **Export/restore** — password-encrypted backup of all secrets
- **Local history** — encrypted at rest via SQLCipher

Full architecture: [`docs/architecture.md`](docs/architecture.md)

## Get it

Download the latest APK from [GitHub Releases](https://github.com/aadegtyarev/open-webdav-messenger/releases).

Or build from source:

```bash
./gradlew assembleDebug
# APK at app/build/outputs/apk/debug/app-debug.apk
```

Requires JDK 17+ and Android SDK. The Gradle wrapper handles the rest.

## Development

```bash
# Run the full build-beat quality suite
node .ai-dev/quality/run.mjs build

# Individual gates
./gradlew test          # JVM unit tests
./gradlew ktlintCheck   # Kotlin style
./gradlew lint          # Android lint
```

See `AGENTS.md` for AI-assisted development workflow. Protocol spec: [`docs/protocol/webdav-layout.md`](docs/protocol/webdav-layout.md).

## License

GNU Affero General Public License v3.0 (AGPL-3.0) — see [`LICENSE`](LICENSE).

Copyleft: the source stays open. Anyone distributing a modified version — or running it as a network service — must release their source under the same terms.
