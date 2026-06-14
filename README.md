# Open WebDAV Messenger

A simple, end-to-end encrypted text messenger for Android that uses a public cloud disk (Yandex.Disk, Nextcloud, any WebDAV share) as its only server. No dedicated backend — the file disk you already control is the transport, and private messages are encrypted client-side so the disk operator sees only ciphertext.

→ What it does, for whom, and current limits: [`docs/product.md`](docs/product.md).

## Quick start

```bash
# Android app — build with Gradle (toolchain details filled as the project takes shape)
./gradlew assembleDebug
```

## Architecture

Native Android (Kotlin + Jetpack Compose). Background polling via WorkManager, WebDAV transport over OkHttp, audited crypto primitives (libsodium), local cache in SQLite/Room. The cloud disk is treated as an untrusted transport — private chat content never leaves the device unencrypted.

See `docs/architecture.md` for full decisions and constraints.

## Development

```bash
./gradlew test
./gradlew ktlintCheck
./gradlew lint
```

See `AGENTS.md` for AI-assisted development workflow.

## License

GNU Affero General Public License v3.0 (AGPL-3.0) — see [`LICENSE`](LICENSE).

A copyleft license: the source stays open, and anyone who distributes a modified
version — or runs it as a network service (e.g. the future Telegram gateway) —
must release their source under the same terms. Commercial use is permitted.
