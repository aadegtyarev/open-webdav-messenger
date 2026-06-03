# Stack notes

Living document. Initialised at bootstrap, extended on every feature that touches a new external system.
Maintained by `pm-stack-researcher`. Read by `pm-plan`, `pm-architect`, `pm-coder`, `pm-plan-checker`.

**Last full review:** 2026-06-03

---

## How this document is used

- **pm-plan** reads it before drafting a plan that touches any listed component. If the feature touches a component that is missing here, `/pm-plan` spawns `pm-stack-researcher` to extend this document **before** continuing.
- **pm-architect** reads it when proposing variants — stack constraints are part of the trade-off space.
- **pm-coder** reads it before writing a mapping, handler, schema, or any integration code for a listed component. On contradiction between task and stack-notes, coder stops and escalates — no fallback to WebSearch.
- **pm-plan-checker** checks every diff against the relevant entries. Code that contradicts an idiom or constraint listed here is **blocking** with a citation back to this file.

If this document is missing or empty for a component the feature touches — that is a protocol-level defect, not a content gap. `/pm-bootstrap` or `/pm-plan` should have caught it.

---

## Platform filesystem layout

> Android app — no FHS / partition-survival model. The relevant boundary is **app-private storage on the device** vs **the remote WebDAV disk**. These are the two persistence tiers; the rule below names which data is allowed in each.

- **Target platform:** Native Android app (Kotlin). No dedicated backend; the WebDAV disk is the only transport.
- **Persistence tiers and survival rules:**
  - **App-private internal storage** (`Context.filesDir`, `Context.dataDir`, app-private databases) — wiped on app uninstall / "Clear data". Not readable by other apps on non-rooted devices. This is where the Room cache, derived keys (via Keystore-wrapped storage), and any plaintext live. Source: <https://developer.android.com/training/data-storage/app-specific>
  - **Android Keystore** — hardware-backed key container; key material never enters app process memory in extractable form. Used to wrap the passphrase-derived chat keys. Source: <https://developer.android.com/privacy-and-security/keystore>
  - **Remote WebDAV disk** (Yandex.Disk / Nextcloud) — the transport. Only **ciphertext** and protocol metadata may be written here. Never write a passphrase, a derived key, or plaintext private-chat content to the disk. (Project invariant — public chats use a shared key with explicit warning.)
- **Persistence boundary rule:** keys and plaintext stay device-local (app-private storage + Keystore); only AEAD ciphertext + non-secret protocol files cross to the WebDAV disk. This boundary is the security contract of the product.
- **Read-only rootfs / install-image paths:** N/A — Android apps do not install to a survivable system partition; APK assets are read-only and replaced on update.
- **Source:** <https://developer.android.com/training/data-storage> ; <https://developer.android.com/privacy-and-security/keystore>
- **Last reviewed:** 2026-06-03

---

## Components

### Kotlin (language)

- **Role in this project:** The implementation language for the whole app.
- **Canonical docs:** <https://kotlinlang.org/docs/home.html>
- **Spec / reference:** Null safety — <https://kotlinlang.org/docs/null-safety.html> ; coroutines — <https://kotlinlang.org/docs/coroutines-overview.html>
- **Required validators:**
  - `./gradlew ktlintCheck` (or `./gradlew detekt`) — static analysis / style; run before every commit. Gates: style + a class of code smells. See Gradle component for which one the project wires in.
  - `./gradlew test` — JVM unit tests; gates logic correctness off-device.
- **Idioms and constraints** (each item: rule + source URL):
  - Non-nullable types cannot hold `null`; nullable types are declared with `?` and cannot be dereferenced without a null check. Prefer safe-call `?.` and Elvis `?:` over `!!`. Source: <https://kotlinlang.org/docs/null-safety.html>
  - The not-null assertion `!!` throws `NullPointerException` if the value is null — "Avoid `!!` unless you're absolutely certain the value is not null." Treat each `!!` in WebDAV/crypto paths as a defect candidate. Source: <https://kotlinlang.org/docs/null-safety.html#not-null-assertion-operator>
  - **Platform types from Java interop are not null-checked by the compiler** — Java can return `null` unexpectedly, so values crossing the Java boundary (many crypto/HTTP libraries are Java) must be treated as nullable. Source: <https://kotlinlang.org/docs/null-safety.html#nullability-and-java-interoperation>
  - Blocking I/O (network, disk, crypto KDF) must not run on the main/UI dispatcher; use coroutines on `Dispatchers.IO`. Source: <https://kotlinlang.org/docs/coroutines-and-channels.html> and Android main-thread rules (see Room / Compose).
- **Known gotchas:**
  - Argon2id KDF is intentionally slow (memory-hard). Running it inside a coroutine on `Dispatchers.Default` can starve other CPU-bound work; isolate it. (Constraint follows from libsodium opslimit/memlimit — see Crypto component, <https://doc.libsodium.org/password_hashing/default_phf>.)
- **Last reviewed:** 2026-06-03

### Android SDK / app platform (target platform)

- **Role in this project:** Host platform. The whole product depends on periodic background polling of a WebDAV disk, so OS background-execution limits are load-bearing.
- **Canonical docs:** <https://developer.android.com/develop/background-work/background-tasks>
- **Spec / reference:** Doze & App Standby — <https://developer.android.com/training/monitoring-device-state/doze-standby> ; App Standby Buckets — <https://developer.android.com/topic/performance/appstandby>
- **Required validators:**
  - `./gradlew lint` — Android Lint; gates manifest, permission, API-level, and security issues. Source: <https://developer.android.com/studio/write/lint>
- **Idioms and constraints** (each item: rule + source URL):
  - **Doze defers nearly all background work.** While Doze is active the system "Suspends network access", "Defers standard `AlarmManager` alarms ... to the next maintenance window", and "Doesn't let `JobScheduler` run (and by extension, `WorkManager` tasks don't run, since it uses `JobScheduler` internally)." Deferred work runs only in periodic **maintenance windows**, which "the system schedules ... less frequently for longer inactivity periods." Source: <https://developer.android.com/training/monitoring-device-state/doze-standby>
  - **App Standby Buckets throttle how often background jobs run.** "The bucket determines how frequently the app's jobs run and how often the app can trigger alarms." The **Restricted** bucket allows jobs "once per day in a 10-minute batched session." An app the user rarely opens will drift toward Rare/Restricted and poll far less often than the configured interval. Source: <https://developer.android.com/topic/performance/appstandby>
  - **Alarms that fire during Doze are rate-limited:** `setAndAllowWhileIdle()` / `setExactAndAllowWhileIdle()` "can[not] fire alarms more than once per nine minutes, per app." Source: <https://developer.android.com/training/monitoring-device-state/doze-standby>
  - **Apps generally cannot start a foreground service while in the background (Android 12+).** "In most cases, apps can't launch foreground services when the apps are in the background." Exemptions include high-priority FCM and user-initiated/visible tasks. Source: <https://developer.android.com/develop/background-work/background-tasks>
  - **Android 14+ requires a declared foreground-service type**, and several types now have a recommended alternative API (data sync → User-Initiated Data Transfer; `shortService` for <3-minute critical tasks). Source: <https://developer.android.com/develop/background-work/background-tasks>
  - **Design consequence:** with no backend and no FCM push, the disk cannot notify the device; the app must poll. The OS will never grant "reliable polling every N seconds in the background." A user-configurable interval below the platform floor is **aspirational, not guaranteed** — see WorkManager gotcha. To poll on a tight cadence the app must run a user-visible **foreground service**; otherwise it is bound to the ~15-min periodic floor and further throttled by Doze/buckets. Source: <https://developer.android.com/develop/background-work/background-tasks> ; <https://developer.android.com/topic/performance/appstandby>
- **Known gotchas:**
  - "App Standby ... allows idle apps network access approximately once a day" — an app the user does not open for days may effectively stop polling in the background until reopened. Source: <https://developer.android.com/training/monitoring-device-state/doze-standby>
  - "Beginning with Android 12, most foreground services do not show notifications to the user until they've been running for 10 seconds" — affects UX expectations for a polling foreground service. Source: <https://developer.android.com/develop/background-work/background-tasks>
- **Last reviewed:** 2026-06-03

### Jetpack Compose (UI toolkit)

- **Role in this project:** Declarative UI for the chat surface (message list, chat list, settings incl. the polling-interval control).
- **Canonical docs:** <https://developer.android.com/develop/ui/compose/documentation>
- **Spec / reference:** Thinking in Compose — <https://developer.android.com/develop/ui/compose/mental-model> ; state & state hoisting — <https://developer.android.com/develop/ui/compose/state>
- **Required validators:**
  - `./gradlew test` / Compose UI tests via `createComposeRule` — gates UI logic. Source: <https://developer.android.com/develop/ui/compose/testing>
  - `./gradlew lint` — flags Compose-specific issues.
- **Idioms and constraints** (each item: rule + source URL):
  - **Composable functions must be side-effect free.** "Never depend on side-effects from executing composable functions, since a function's recomposition may be skipped." Do not mutate ViewModel/SharedPreferences/shared state directly inside a composable. Source: <https://developer.android.com/develop/ui/compose/mental-model#side-effects>
  - **Recomposition can run in any order and in parallel** — "you might assume that the code is run in the order it appears. But this isn't guaranteed to be true." Composables must be thread-safe and order-independent. Source: <https://developer.android.com/develop/ui/compose/mental-model#parallel>
  - **Composables run frequently** ("as often as every frame") — move expensive work (network, DB, crypto) off the composable into a ViewModel/background dispatcher and pass results in as state. Source: <https://developer.android.com/develop/ui/compose/mental-model#frequent>
  - **Hoist state**: pass data down as parameters and events up as callbacks; the ViewModel owns state and mutates it off the UI thread. Source: <https://developer.android.com/develop/ui/compose/state#state-hoisting>
- **Known gotchas:**
  - Reading a `mutableStateOf` that you also write inside the same composition can cause infinite recomposition; mutate state in event callbacks or `LaunchedEffect`, not in the composable body. Source: <https://developer.android.com/develop/ui/compose/side-effects>
- **Last reviewed:** 2026-06-03

### WorkManager (periodic background polling)

- **Role in this project:** Chosen mechanism for periodic background polling of the WebDAV disk. This is the most constrained component — the user-configurable polling interval collides with platform floors.
- **Canonical docs:** <https://developer.android.com/develop/background-work/background-tasks/persistent>
- **Spec / reference:** PeriodicWorkRequest API — <https://developer.android.com/reference/androidx/work/PeriodicWorkRequest> ; OutOfQuotaPolicy — <https://developer.android.com/reference/androidx/work/OutOfQuotaPolicy>
- **Required validators:**
  - `./gradlew test` with `androidx.work:work-testing` (`TestDriver`) — gates Worker logic. Source: <https://developer.android.com/develop/background-work/background-tasks/persistent/how-to/integration-testing>
- **Idioms and constraints** (each item: rule + source URL):
  - **PeriodicWorkRequest has a hard 15-minute floor.** "The minimum repeat interval that can be defined is 15 minutes, the same as the JobScheduler API." The repeat interval must be ≥ `PeriodicWorkRequest.MIN_PERIODIC_INTERVAL_MILLIS` (900000 ms = 15 min) and the flex interval ≥ `MIN_PERIODIC_FLEX_MILLIS`. Requesting a shorter interval does not give a shorter interval — WorkManager clamps it to the 15-min minimum. Source: <https://developer.android.com/develop/background-work/background-tasks/persistent/getting-started/define-work#schedule_periodic_work> ; <https://developer.android.com/reference/androidx/work/PeriodicWorkRequest>
  - **15 min is a floor, not a guarantee.** Even with a 15-min request, Doze and App Standby Buckets defer execution further (maintenance windows, restricted-bucket once-per-day batching). See Android platform component. Source: <https://developer.android.com/topic/performance/appstandby>
  - **Expedited work is for short, user-important tasks and is quota-limited.** `setExpedited()` runs work "as quickly as possible" but "A system-level quota that limits foreground execution time determines whether an expedited job can start." When out of quota, `OutOfQuotaPolicy` chooses `RUN_AS_NON_EXPEDITED_WORK_REQUEST` (degrade to normal) or `DROP_WORK_REQUEST`. Source: <https://developer.android.com/develop/background-work/background-tasks/persistent/getting-started/define-work#expedited> ; <https://developer.android.com/reference/androidx/work/OutOfQuotaPolicy>
  - **Pre-Android-12, expedited work runs via a foreground service** (needs `getForegroundInfo()`); Android 12+ delegates to JobScheduler expedited jobs. Source: <https://developer.android.com/develop/background-work/background-tasks/persistent/getting-started/define-work#expedited>
  - **For sub-15-min cadence, use a foreground service (long-running Worker) or re-enqueue a OneTimeWorkRequest from inside the Worker** — these are the only documented escapes from the periodic floor, and the foreground-service path costs a persistent notification. Source: <https://developer.android.com/develop/background-work/background-tasks> ; <https://developer.android.com/develop/background-work/background-tasks/persistent/how-to/long-running>
- **Known gotchas:**
  - Chaining/re-enqueuing OneTimeWorkRequests to fake a tight loop still gets throttled by Doze/buckets and drains battery; the OS does not reward this pattern. Source: <https://developer.android.com/topic/performance/appstandby> (bucket throttling applies to all deferred jobs).
  - On some OEM devices (aggressive battery managers) periodic work may not fire at all unless the app is whitelisted from battery optimization. Source: <https://developer.android.com/develop/background-work/background-tasks/persistent> (general background-restriction guidance); cross-vendor behavior is documented community-wide at <https://dontkillmyapp.com/>.
  - **Open design note:** the project's "user-configurable polling interval" must clamp to ≥15 min for the WorkManager path, and offer a foreground-service mode if the user wants tighter polling. The architect must decide which.
- **Last reviewed:** 2026-06-03

### OkHttp + WebDAV layer (sardine-android or hand-rolled)

- **Role in this project:** HTTP transport to the WebDAV disk. WebDAV verbs ride on top: PROPFIND (list/poll), GET (read message files), PUT (write), MKCOL (create chat folders), DELETE, plus ETag/If-Match conditional requests for optimistic concurrency.
- **Canonical docs:** OkHttp — <https://square.github.io/okhttp/> ; sardine-android — <https://github.com/thegrizzlylabs/sardine-android>
- **Spec / reference:** WebDAV — RFC 4918 <https://datatracker.ietf.org/doc/html/rfc4918> ; Yandex.Disk WebDAV — <https://yandex.com/dev/disk/webdav/> ; Nextcloud WebDAV — <https://docs.nextcloud.com/server/latest/developer_manual/client_apis/WebDAV/basic.html>
- **Required validators:**
  - `./gradlew test` with `okhttp3.mockwebserver.MockWebServer` — gates request shaping (verbs, headers, conditional requests) without a live disk. Source: <https://github.com/square/okhttp/tree/master/mockwebserver>
- **Idioms and constraints** (each item: rule + source URL):
  - **Create a single OkHttpClient and reuse it.** "OkHttp performs best when you create a single OkHttpClient instance and reuse it for all of your HTTP calls. This is because each client holds its own connection pool and thread pools." Source: <https://square.github.io/okhttp/5.x/okhttp/okhttp3/-ok-http-client/index.html>
  - **Always close the response body.** OkHttp examples use try-with-resources (`try (Response response = ...)`); an unclosed response body leaks a connection. Source: <https://square.github.io/okhttp/> (response handling examples).
  - **WebDAV verbs (RFC 4918):** PROPFIND (retrieve properties / list), PROPPATCH, MKCOL (create collection), GET/HEAD, PUT, DELETE, COPY, MOVE, LOCK/UNLOCK. Source: <https://datatracker.ietf.org/doc/html/rfc4918#section-9>
  - **PROPFIND Depth header controls listing scope:** "0" = the resource only, "1" = resource + direct members, "infinity" = whole subtree. Servers MUST support 0 and 1; infinity SHOULD be supported but "MAY be disabled, due to the performance and security concerns." Poll the chat folder with Depth: 1, never rely on infinity. Source: <https://datatracker.ietf.org/doc/html/rfc4918#section-9.1>
  - **Optimistic concurrency via ETags + If header.** "Correct use of ETags is even more important in a distributed authoring environment ... to avoid the lost-update problem." Use the `If` / `If-Match` header with the resource's ETag (returned as `d:getetag` in PROPFIND) on PUT to detect a competing writer. Source: <https://datatracker.ietf.org/doc/html/rfc4918#section-8.6> ; <https://datatracker.ietf.org/doc/html/rfc4918#section-10.4>
  - **Nextcloud base path** is `/remote.php/dav/files/{user}/...`; PROPFIND returns `d:getetag`, `d:getlastmodified`, `d:getcontentlength`, `d:resourcetype`. Source: <https://docs.nextcloud.com/server/latest/developer_manual/client_apis/WebDAV/basic.html>
  - **Yandex.Disk WebDAV endpoint** is `https://webdav.yandex.com` (a.k.a. `webdav.yandex.ru`), always TLS on 443; authenticate with the Yandex login + an **app password for Files/WebDAV** (not the account password), or an OAuth token. Source: <https://yandex.com/dev/disk/webdav/> ; <https://www.davx5.com/tested-with/yandex>
- **Known gotchas:**
  - **Yandex.Disk rate-limits PROPFIND and returns `429 Too Many Requests`** under frequent polling — directly relevant to the polling design; back off on 429 and minimise round-trips. Source: <https://github.com/kopia/kopia/issues/88>
  - **sardine-android is distributed via JitPack** (`com.github.thegrizzlylabs:sardine-android`), forked from Sardine with OkHttp + SimpleXml replacing Apache HTTP + JAXB — confirm it exposes ETag/If-Match and Depth control before relying on it; otherwise hand-roll PROPFIND/PUT over OkHttp. Source: <https://github.com/thegrizzlylabs/sardine-android>
  - Providers differ on whether WebDAV LOCK is supported; do not depend on LOCK for concurrency — use ETag conditional PUT, which is portable across providers. Source: <https://datatracker.ietf.org/doc/html/rfc4918#section-7> (LOCK is optional/advisory) ; provider variance noted at <https://www.davx5.com/tested-with/yandex>.
- **Last reviewed:** 2026-06-03

### Crypto library — libsodium (lazysodium-android) vs Google Tink

> Architect's decision, not pre-empted here. The decisive axis for this project: the product derives a **per-chat key from a passphrase**, which needs a **memory-hard KDF (Argon2id)** plus an **AEAD**. Cited trade-offs below.

- **Role in this project:** Client-side AEAD encryption of message content + passphrase-to-key derivation. Public chats use a shared key (with explicit warning); private chats derive a key from a user passphrase.
- **Canonical docs:** libsodium — <https://doc.libsodium.org/> ; lazysodium-android — <https://github.com/terl/lazysodium-android> ; Tink — <https://developers.google.com/tink>
- **Spec / reference:** libsodium pwhash (Argon2id) — <https://doc.libsodium.org/password_hashing/default_phf> ; libsodium AEAD (XChaCha20-Poly1305) — <https://doc.libsodium.org/secret-key_cryptography/aead> ; Tink AEAD — <https://developers.google.com/tink/aead>
- **Required validators:**
  - `./gradlew connectedAndroidTest` (instrumented) — native-`.so` crypto (lazysodium) must be exercised on a device/emulator, not the JVM, to catch ABI/packaging failures. Source: <https://github.com/terl/lazysodium-android> (uses JNA-backed native lib).
- **Idioms and constraints** (each item: rule + source URL):
  - **libsodium provides Argon2id natively.** `crypto_pwhash` uses Argon2id by default: "Since version 1.0.15, libsodium's default algorithm is Argon2id." Presets: `INTERACTIVE` (64 MiB), `MODERATE` (256 MiB), `SENSITIVE` (1024 MiB). This is the memory-hard KDF the project needs for passphrase→key. Source: <https://doc.libsodium.org/password_hashing/default_phf>
  - **libsodium provides XChaCha20-Poly1305 AEAD** (192-bit nonce — safe to pick at random). Clean AEAD with associated data. Source: <https://doc.libsodium.org/secret-key_cryptography/aead>
  - **Tink provides AEAD (AES-256-GCM, AES-128-GCM, ChaCha20-Poly1305 / XChaCha20-Poly1305)** via keyset handles. Source: <https://developers.google.com/tink/aead>
  - **Tink does NOT provide a password-based / memory-hard KDF (no Argon2id).** Tink's maintainers decline built-in password-based key derivation: "there is currently no built-in support for deriving encryption keys directly from passwords." Using Tink means deriving the key with a separate library (e.g. libsodium/argon2) and feeding it in — so a Tink-only design cannot satisfy the passphrase requirement alone. Source: <https://github.com/google/tink/issues/121> ; <https://github.com/google/tink/issues/347>
  - **Tink keys live behind a `KeysetHandle`**; on Android `AndroidKeysetManager` can wrap the keyset with an Android Keystore master key. Source: <https://developers.google.com/tink/key-management-overview>
- **Known gotchas:**
  - **lazysodium-android ships native `.so` via JNA** — added as `com.goterl:lazysodium-android:VERSION@aar` **plus** `net.java.dev.jna:jna:VERSION@aar`. Native packaging means per-ABI `.so` must be bundled; missing an ABI = `UnsatisfiedLinkError` at runtime on that device. Verify on real ABIs. Source: <https://github.com/terl/lazysodium-android>
  - **Tink → libsodium split:** if Tink is chosen for AEAD, Argon2id still has to come from elsewhere, so the project likely ends up with libsodium present regardless — argues for libsodium-only unless Tink's key-management is specifically wanted. Source: <https://github.com/google/tink/issues/121>
  - Argon2id at MODERATE/SENSITIVE is intentionally slow (~0.7 s / ~3.5 s on a desktop i7) and memory-heavy; on a phone it is slower — run it off the UI thread and pick a preset that survives low-RAM devices. Source: <https://doc.libsodium.org/password_hashing/default_phf>

#### Public-key primitives (X25519 identity substrate)

> Extension for the X25519 identity feature. The symmetric side above (Argon2id + XChaCha20-Poly1305 AEAD) is unchanged. The same lazysodium artifacts cover this: **`com.goterl:lazysodium-android`** in the app and **`com.goterl:lazysodium-java`** for JVM tests — the maintainers state lazysodium-android "has the same API as this library, so you can share code easily!", so `Box`/`Sign`/`GenericHash` lazy interfaces and their constants are identical across both. Source: <https://github.com/terl/lazysodium-java>. Package: `com.goterl.lazysodium.interfaces.{Box,Sign,GenericHash}`; the lazy entry points are `LazySodiumAndroid` / `LazySodiumJava` implementing `Box.Lazy`, `Sign.Lazy`, `GenericHash.Lazy`. The `connectedAndroidTest` gate (per-ABI `.so`) applies unchanged to every public-key path below — these are native calls.

- **Idioms and constraints — public-key (each item: rule + source URL):**
  - **`crypto_box` — authenticated public-key encryption (X25519 + XSalsa20-Poly1305).** Keypair via `crypto_box_keypair`; both keys are 32 bytes (`crypto_box_PUBLICKEYBYTES` = 32, `crypto_box_SECRETKEYBYTES` = 32). Nonce is 24 bytes (`crypto_box_NONCEBYTES` = 24), MAC 16 bytes (`crypto_box_MACBYTES` = 16). Combined-mode API is `crypto_box_easy(c, m, mlen, n, pk, sk)` / `crypto_box_open_easy(m, c, clen, n, pk, sk)`. Underlying primitives: X25519 key exchange, XSalsa20 cipher, Poly1305 MAC. Source: <https://doc.libsodium.org/public-key_cryptography/authenticated_encryption>
  - **`crypto_box` nonce rule — same nonce both sides, unique per (key, message).** "The nonce doesn't have to be confidential, but it should be used with just one invocation of `crypto_box_easy()` for a particular pair of public and secret keys." The sender and receiver use the **same** nonce value for a given message; it is not secret and may travel alongside the ciphertext, but it must never be reused for the same key pair. With a 24-byte nonce, random generation via `randombytes_buf()` has negligible collision risk. Source: <https://doc.libsodium.org/public-key_cryptography/authenticated_encryption>
  - **`crypto_box_beforenm` — precomputed shared key for a KDF / chat key.** `crypto_box_beforenm(k, pk, sk)` computes the X25519 shared secret once (`crypto_box_BEFORENMBYTES` = 32) so repeated messages reuse it via the `*_afternm` calls. The 32-byte shared secret is the natural input to a KDF (e.g. `crypto_kdf` / generichash) to derive a per-chat symmetric key — do not feed the raw shared secret directly to the AEAD; derive from it. Source: <https://doc.libsodium.org/public-key_cryptography/authenticated_encryption>
  - **`crypto_box_seal` — anonymous sealed box (rotation primitive).** `crypto_box_seal(c, m, mlen, pk)` encrypts to a recipient public key only; libsodium generates an **ephemeral** sender keypair internally and "The secret key is overwritten and is not accessible after this function returns." Recipient opens with `crypto_box_seal_open(m, c, clen, pk, sk)` using their own keypair. Overhead `crypto_box_SEALBYTES` = 48. This is the primitive for "encrypt the new disk password per remaining member" on rotation — seal once per recipient public key. Source: <https://doc.libsodium.org/public-key_cryptography/sealed_boxes>
  - **Sealed box gives confidentiality but NOT sender authentication — flag this property.** "While the recipient can verify the integrity of the message, they cannot verify the identity of the sender" and "Without additional data, a message cannot be correlated with the identity of its sender." Anyone holding a recipient public key can seal to it; if rotation messages must be attributable, the payload (or an outer envelope) must be **separately signed** with the sender's Ed25519 key. Source: <https://doc.libsodium.org/public-key_cryptography/sealed_boxes>
  - **`crypto_sign` — Ed25519 signatures (directory-entry authentication).** Keypair via `crypto_sign_keypair`; `crypto_sign_PUBLICKEYBYTES` = 32, `crypto_sign_SECRETKEYBYTES` = 64, signature `crypto_sign_BYTES` = 64, seed `crypto_sign_SEEDBYTES` = 32. Prefer detached mode for directory entries: `crypto_sign_detached(sig, siglen, m, mlen, sk)` and verify with `crypto_sign_verify_detached(sig, m, mlen, pk)` (returns 0 on success, -1 on failure — treat -1 as a hard reject, never "best effort"). The 64-byte secret key embeds the seed and the public key: "The secret key includes the seed ... and public key." Source: <https://doc.libsodium.org/public-key_cryptography/public-key_signatures>
  - **One identity, two key types — conversion exists, but libsodium recommends separate keys.** A single Ed25519 identity can be converted to its X25519 (Curve25519) form with `crypto_sign_ed25519_pk_to_curve25519(x25519_pk, ed25519_pk)` and `crypto_sign_ed25519_sk_to_curve25519(x25519_sk, ed25519_sk)`, so one published key serves both signing and DH. **Caveat (libsodium's own words):** "If you can afford it, using distinct keys for signing and for encryption is still highly recommended. Signing keys are usually long-term keys, while keys used for key exchange should rather be ephemeral." The conversion is cryptographically supported; the trade-off is one publishable identity key (simpler directory, simpler fingerprint) vs. the libsodium-recommended separation. Architect/coder decides — see Open question. Source: <https://doc.libsodium.org/advanced/ed25519-curve25519>
  - **Safety number / fingerprint — deterministic and symmetric.** Use `crypto_generichash` (BLAKE2b) over **both** parties' public identity keys to produce a stable out-of-band verification value. BLAKE2b is deterministic — "a message will always have the same fingerprint" — and `crypto_generichash_BYTES` (default 32, MIN 16, MAX 64) sets the digest size. To make both devices show the **same** value, follow the Signal pattern: derive one fingerprint per party from that party's public identity key, then **sort** the two and concatenate (Signal: "a sorted concatenation of two ... individual numeric fingerprints", rendered as digit groups). The sort is what guarantees symmetry regardless of which device computes it. Sources: <https://doc.libsodium.org/hashing/generic_hashing> ; <https://signal.org/blog/safety-number-updates/>
  - **Identity secret-key storage falls under the existing Keystore rule.** The identity **secret** key (Ed25519 `sk`, and any derived X25519 `sk`) is device-local secret material — same constraint as chat keys: Android Keystore-wrapped in app-private storage, **never** written to the WebDAV disk. The identity **public** key is publishable (it is the whole point of a directory entry). See *Android Keystore* component and *Platform filesystem layout*. Source: <https://developer.android.com/privacy-and-security/keystore>

- **lazysodium API surface — public-key (constants verified against the interface source):**
  - **`Box.Lazy`** (`com.goterl.lazysodium.interfaces.Box`): `KeyPair cryptoBoxKeypair()`, `KeyPair cryptoBoxSeedKeypair(byte[] seed)`, `String cryptoBoxEasy(String message, byte[] nonce, KeyPair keyPair)`, `String cryptoBoxOpenEasy(String cipherText, byte[] nonce, KeyPair keyPair)`, `String cryptoBoxBeforeNm(byte[] publicKey, byte[] secretKey)` / `cryptoBoxBeforeNm(KeyPair)`, `cryptoBoxEasyAfterNm` / `cryptoBoxOpenEasyAfterNm`. **Sealed box methods are named `cryptoBoxSealEasy(String message, Key publicKey)` and `cryptoBoxSealOpenEasy(String cipherText, KeyPair keyPair)`** (lazysodium's `*Easy` suffix — not bare `cryptoBoxSeal`). Constants: `PUBLICKEYBYTES`=32, `SECRETKEYBYTES`=32, `NONCEBYTES`=24, `MACBYTES`=16, `BEFORENMBYTES`=32, `SEALBYTES`=48. Source: <https://github.com/terl/lazysodium-java/blob/master/src/main/java/com/goterl/lazysodium/interfaces/Box.java>
  - **`Sign.Lazy`** (`com.goterl.lazysodium.interfaces.Sign`): `KeyPair cryptoSignKeypair()`, `KeyPair cryptoSignSeedKeypair(byte[] seed)`, `String cryptoSign(String message, Key secretKey)`, `String cryptoSignOpen(String signedMessage, Key publicKey)`, `String cryptoSignDetached(String message, Key secretKey)`, `boolean cryptoSignVerifyDetached(String signature, String message, Key publicKey)`, and **`KeyPair convertKeyPairEd25519ToCurve25519(KeyPair ed25519KeyPair)`** for the one-identity conversion (the `Sign.Native` low-level forms are `convertPublicKeyEd25519ToCurve25519(byte[] curve, byte[] ed)` / `convertSecretKeyEd25519ToCurve25519(byte[] curve, byte[] ed)`). Constants: `ED25519_PUBLICKEYBYTES`/`PUBLICKEYBYTES`=32, `ED25519_SECRETKEYBYTES`/`SECRETKEYBYTES`=64, `ED25519_BYTES`/`BYTES`=64, `SEEDBYTES`=32, `CURVE25519_PUBLICKEYBYTES`=32. Source: <https://github.com/terl/lazysodium-java/blob/master/src/main/java/com/goterl/lazysodium/interfaces/Sign.java>
  - **`GenericHash.Lazy`** (`com.goterl.lazysodium.interfaces.GenericHash`): `String cryptoGenericHash(String in)`, `String cryptoGenericHash(String in, Key key)`, plus streaming `cryptoGenericHashInit(byte[] state, Key key, int outLen)` / `cryptoGenericHashUpdate` / `cryptoGenericHashFinal`. Constants: `BYTES`=32, `BYTES_MIN`=16, `BYTES_MAX`=64, `KEYBYTES`=32, `KEYBYTES_MAX`=64 (`KEYBYTES_MIN` is flagged unreliable in lazysodium's own tests — do not use a sub-`KEYBYTES` key). Source: <https://github.com/terl/lazysodium-java/blob/master/src/main/java/com/goterl/lazysodium/interfaces/GenericHash.java>

- **Known gotchas — public-key:**
  - **lazysodium seal naming differs from the C name.** libsodium C is `crypto_box_seal`; lazysodium's lazy method is **`cryptoBoxSealEasy` / `cryptoBoxSealOpenEasy`**. Searching for `cryptoBoxSeal(` alone will miss it. Source: <https://github.com/terl/lazysodium-java/blob/master/src/main/java/com/goterl/lazysodium/interfaces/Box.java>
  - **`crypto_sign_ed25519_sk_to_curve25519` only consumes the seed half.** It "only reads the first 32 bytes (the 32 byte seed ...) and ignores the 32 remaining bytes" of the Ed25519 secret key — relevant if any code slices the 64-byte sk manually before conversion. Source: <https://doc.libsodium.org/advanced/ed25519-curve25519>
  - **Do not use the raw `crypto_box_beforenm` shared secret as the chat key.** It is a DH output, not a uniformly-distributed key destined for an AEAD; run it through a KDF / generichash first (project already uses Argon2id/AEAD — derive the public-key chat key the same disciplined way). Source: <https://doc.libsodium.org/public-key_cryptography/authenticated_encryption> (precomputation section) ; KDF practice <https://doc.libsodium.org/key_derivation>
  - **Sealed box is unauthenticated as to sender — never infer "who rotated the key" from a sealed payload.** If rotation provenance matters, wrap or co-deliver an Ed25519 detached signature; the sealed box alone proves only that the holder of the recipient sk could open it. Source: <https://doc.libsodium.org/public-key_cryptography/sealed_boxes>
- **Last reviewed:** 2026-06-03

### Android Keystore / EncryptedSharedPreferences (Jetpack Security)

- **Role in this project:** Store the passphrase-derived per-chat keys on the device, wrapped so they never appear in plaintext on disk and never leave for the WebDAV disk.
- **Canonical docs:** Android Keystore — <https://developer.android.com/privacy-and-security/keystore> ; Jetpack Security — <https://developer.android.com/jetpack/androidx/releases/security>
- **Spec / reference:** Keystore key generation — <https://developer.android.com/privacy-and-security/keystore#GeneratingANewPrivateKey>
- **Required validators:**
  - `./gradlew connectedAndroidTest` — Keystore is device-backed; key wrap/unwrap must be tested instrumented, not on the JVM. Source: <https://developer.android.com/privacy-and-security/keystore>
- **Idioms and constraints** (each item: rule + source URL):
  - **Android Keystore keeps key material out of the app process** and (on supported hardware) in a TEE/StrongBox — extraction is resisted even on a compromised app. Use it to wrap the chat keys rather than storing raw key bytes. Source: <https://developer.android.com/privacy-and-security/keystore>
  - **`androidx.security:security-crypto` (EncryptedSharedPreferences, EncryptedFile, MasterKeys) is DEPRECATED.** Deprecated at 1.1.0-beta01 (2025-06-04), confirmed through stable 1.1.0 (2025-07-30): "Deprecated all APIs in favour of existing platform APIs and direct use of Android Keystore." **Do not build new storage on EncryptedSharedPreferences** — use Android Keystore directly (e.g. an AES/GCM key generated in Keystore to encrypt blobs, or DataStore + Keystore-wrapped key). Source: <https://developer.android.com/jetpack/androidx/releases/security>
- **Known gotchas:**
  - EncryptedSharedPreferences had real-world Keystore crashes on key creation across OEMs (pre-deprecation), another reason to prefer direct Keystore. Source: <https://issuetracker.google.com/issues/370009394>
  - **Never persist a derived chat key or passphrase to the WebDAV disk** — only to Keystore-wrapped app-private storage. (Product security invariant; see Platform filesystem layout.) Source: <https://developer.android.com/privacy-and-security/keystore>
- **Last reviewed:** 2026-06-03

### Room / SQLite (local offline-first cache)

- **Role in this project:** Local offline-first cache of messages and protocol/key metadata, so the UI is responsive between polls and works offline.
- **Canonical docs:** <https://developer.android.com/training/data-storage/room>
- **Spec / reference:** Async queries — <https://developer.android.com/training/data-storage/room/async-queries> ; migrations — <https://developer.android.com/training/data-storage/room/migrating-db-versions>
- **Required validators:**
  - `./gradlew test` (with Room in-memory DB) / `connectedAndroidTest` for migration tests — gates schema + DAO correctness. Source: <https://developer.android.com/training/data-storage/room/testing-db>
  - Room schema export (`room.schemaLocation`) — checked-in JSON schema validates migrations.
- **Idioms and constraints** (each item: rule + source URL):
  - **No database access on the main thread.** "To prevent queries from blocking the UI, Room does not allow database access on the main thread." Use `suspend` DAO functions and `Flow` for observable queries; `allowMainThreadQueries()` exists but must be avoided. Source: <https://developer.android.com/training/data-storage/room/async-queries>
  - **SQL is verified at compile time** — invalid queries fail the build, which is a feature; lean on it. Source: <https://developer.android.com/training/data-storage/room>
  - **A schema version bump requires a `Migration`** (or a destructive fallback). Set `exportSchema = true` and check the generated JSON into VCS so migrations are reviewable and testable. Source: <https://developer.android.com/training/data-storage/room/migrating-db-versions>
- **Known gotchas:**
  - `Flow`/`LiveData` queries observe table changes and re-emit; an unbounded message query feeding the UI should be paged (Paging 3) to avoid loading a whole chat history. Source: <https://developer.android.com/training/data-storage/room/accessing-data> (observable queries).
- **Last reviewed:** 2026-06-03

### Gradle (Kotlin DSL) — build & static analysis

- **Role in this project:** Build system and host of all validators (test, lint, style).
- **Canonical docs:** <https://docs.gradle.org/current/userguide/kotlin_dsl.html> ; Android Gradle Plugin — <https://developer.android.com/build>
- **Spec / reference:** ktlint-gradle — <https://github.com/JLLeitschuh/ktlint-gradle> ; detekt — <https://detekt.dev/docs/gettingstarted/gradle> ; Android Lint — <https://developer.android.com/studio/write/lint>
- **Required validators:**
  - `./gradlew test` — JVM unit tests. Source: <https://developer.android.com/build>
  - `./gradlew lint` — Android Lint; "If set to true (default), [`abortOnError`] stops the build if errors are found." Source: <https://developer.android.com/studio/write/lint>
  - `./gradlew ktlintCheck` — ktlint style (jlleitschuh plugin `org.jlleitschuh.gradle.ktlint`); `ktlintFormat` auto-fixes. Source: <https://github.com/JLLeitschuh/ktlint-gradle>
  - `./gradlew detekt` — detekt static analysis; can `failOnSeverity`. (Alternative/complement to ktlint.) Source: <https://detekt.dev/docs/gettingstarted/gradle>
- **Idioms and constraints** (each item: rule + source URL):
  - **Pick one style/static-analysis stack and wire it into the pipeline** — ktlint (format-focused) or detekt (smell-focused), or both; both expose Gradle tasks (`ktlintCheck` / `detekt`). The architect picks; the pipeline below assumes ktlint + lint as the default and notes detekt as alternative. Source: <https://github.com/JLLeitschuh/ktlint-gradle> ; <https://detekt.dev/docs/gettingstarted/gradle>
  - **Android Lint aborts the build on errors by default** (`abortOnError = true`); keep it that way and use a checked-in `lint-baseline.xml` only for pre-existing debt. Source: <https://developer.android.com/studio/write/lint>
- **Known gotchas:**
  - ktlint-gradle only activates on projects that apply the Kotlin plugin — applying it at the root without Kotlin yields no tasks. Source: <https://github.com/JLLeitschuh/ktlint-gradle>
  - Android Lint runs per build variant (`lintDebug`, `lintRelease`); `./gradlew lint` covers the default variant — confirm the gating variant matches CI. Source: <https://developer.android.com/studio/write/lint>
- **Last reviewed:** 2026-06-03


### Traffic compression — java.util.zip vs zstd-jni vs aircompressor

> Architect's decision, not pre-empted here. WebDAV is slow and Yandex.Disk throttles with `429` (see OkHttp/WebDAV component), so message bodies/batches are compressed to cut bytes-over-the-wire. Two hard invariants frame the whole design and are stated as rules below: (1) **compress-then-encrypt** is the only order that saves bytes, and (2) compressing attacker-influenceable data together with secret data in one compression context is a CRIME/BREACH-class leak. Cited trade-offs follow; the library is the architect's call.

- **Role in this project:** Compress message payloads/batches **before** AEAD encryption to reduce the number of bytes PUT to / GET from the WebDAV disk and so reduce round-trip volume and 429 pressure.
- **Canonical docs:** `java.util.zip` — <https://developer.android.com/reference/java/util/zip/package-summary> ; zstd-jni — <https://github.com/luben/zstd-jni> ; aircompressor — <https://github.com/airlift/aircompressor>
- **Spec / reference:** DEFLATE — RFC 1951 <https://datatracker.ietf.org/doc/html/rfc1951> ; zlib format — RFC 1950 <https://datatracker.ietf.org/doc/html/rfc1950> ; gzip format — RFC 1952 <https://datatracker.ietf.org/doc/html/rfc1952> ; Zstandard format — RFC 8878 <https://datatracker.ietf.org/doc/html/rfc8878>
- **Required validators:**
  - None new beyond the existing Gradle gates. `./gradlew test` covers compress→encrypt→decrypt→decompress round-trips off-device; if a **native** option (zstd-jni) is chosen, `./gradlew connectedAndroidTest` is required to exercise the `.so` per ABI (same rule as lazysodium — see Crypto component). No new pipeline command. Source: <https://github.com/luben/zstd-jni> (native `.aar`, per-ABI binaries).
- **Idioms and constraints** (each item: rule + source URL):
  - **`java.util.zip` (Deflater / `DeflaterOutputStream` / `GZIPOutputStream` / `InflaterInputStream`) is part of the Android stdlib, zero-dependency, available on all supported API levels** (the package is in the Android API reference, no minSdk caveat). Raw DEFLATE (RFC 1951) carries no framing; `GZIPOutputStream` adds gzip framing (RFC 1952) with a header + CRC32 trailer. Pick raw `Deflater` (with `nowrap`) for the smallest output when you control both ends; pick gzip only if you need self-describing framing. Source: <https://developer.android.com/reference/java/util/zip/Deflater> ; <https://developer.android.com/reference/java/util/zip/GZIPOutputStream> ; RFC 1951 <https://datatracker.ietf.org/doc/html/rfc1951> ; RFC 1952 <https://datatracker.ietf.org/doc/html/rfc1952>
  - **zstd-jni (`com.github.luben:zstd-jni`) is a JNI binding that embeds the native Zstandard `.so`.** "The binary releases are architecture dependent because we are embedding the native library in the provided Jar file." For Android it is consumed as an `.aar`: `implementation "com.github.luben:zstd-jni:VERSION@aar"`. Native packaging means each shipped ABI needs its `.so`; an ABI not bundled fails with `UnsatisfiedLinkError` at runtime on that device (same packaging risk as lazysodium — see Crypto component). Source: <https://github.com/luben/zstd-jni/blob/master/README.md>
  - **aircompressor is a pure-Java port (no native `.so`) of Zstandard, LZ4, Snappy, LZO and Deflate** — "a set of compression algorithms implemented in pure Java". This avoids per-ABI packaging entirely. **Version split matters:** the legacy `io.airlift:aircompressor` (0.x / 2.x) targets **Java 8+** and is the Android-compatible line; the newer `io.airlift:aircompressor-v3` "requires a Java 22+ virtual machine" and is **not** Android-suitable. Both lines use `sun.misc.Unsafe` for fast memory access. Source: <https://github.com/airlift/aircompressor/blob/master/README.md> ; v2/v3 Java-version split <https://mvnrepository.com/artifact/io.airlift/aircompressor-v3>
  - **Ordering invariant — compress THEN encrypt.** AEAD ciphertext is high-entropy and does not compress, so encrypt-then-compress yields no size benefit; only compress-then-encrypt reduces bytes. Source: <https://www.iacr.org/archive/crypto2001/21390309.pdf> (compression before encryption; encrypted/high-entropy data is incompressible) ; corroborated by BREACH's premise that compression must precede encryption to leak/shrink anything <https://breachattack.com/>.
- **Known gotchas:**
  - **CRIME / BREACH-class leak (compress-then-encrypt side channel).** When attacker-influenceable input and a secret are compressed in the **same compression context**, the compressed (then encrypted) length leaks information about the secret byte-by-byte — "encryption does not hide size", so the length is observable even after encryption. Mitigations the project must consider: never co-compress attacker-controlled data with secrets in one buffer, compress each message independently, and/or length-hiding (random padding) on the wire. Source: <https://breachattack.com/> (mechanism + mitigation list: separating secrets from user input, length hiding, rate-limiting) ; RFC 7457 framing of compression side channels (CRIME/BREACH) <https://datatracker.ietf.org/doc/html/rfc7457#section-2.6>.
  - **Decompressing untrusted input is itself a risk.** A decompressor fed malformed/attacker-crafted compressed bytes (here: anything pulled off the WebDAV disk, decrypted, then inflated) can be driven to misbehave — aircompressor decompressors had an out-of-bounds read (CVE-2024-36114, fixed in 0.27) that "access memory outside the bounds of the given byte arrays" and, because of `sun.misc.Unsafe`, could crash the JVM or leak process memory. Pin a fixed version, bound the decompressed size (zip-bomb guard), and treat decompression failure as a normal error path. Source: <https://github.com/airlift/aircompressor/security/advisories/GHSA-973x-65j7-xcf4> ; <https://security.snyk.io/vuln/SNYK-JAVA-IOAIRLIFT-7164637>
  - **Trade-off summary for the architect (cited above, no pick made here):** `java.util.zip`/DEFLATE = zero dependency, all API levels, modest ratio, lowest risk surface. zstd-jni = best ratio/speed but native `.so` per ABI (packaging + `UnsatisfiedLinkError` risk + larger APK). aircompressor (v2 line) = zstd/lz4 ratio with no native `.so`, but Java-8 line only, relies on `sun.misc.Unsafe`, and carries the decompressor-CVE history. Source (ratio/CPU is workload-dependent — measure on real message sizes): Zstandard format RFC 8878 <https://datatracker.ietf.org/doc/html/rfc8878> ; <https://github.com/luben/zstd-jni> ; <https://github.com/airlift/aircompressor>
- **Last reviewed:** 2026-06-03

### Markdown rendering in Jetpack Compose — mikepenz vs jeziellago vs hand-rolled AnnotatedString

> Architect's decision, not pre-empted here. Messages support a small Markdown subset (bold, italic, inline code, code block, blockquote, link). The rendered content is **untrusted decrypted message text from a remote party**, so the decisive axis is the security surface of link handling, raw-HTML passthrough, and remote-resource loading — not feature richness. Cited options + the security gotcha follow; the library-vs-hand-rolled call is the architect's.

- **Role in this project:** Render the supported Markdown subset of a received message into Compose UI. Input is decrypted, attacker-influenceable text.
- **Canonical docs:** mikepenz multiplatform-markdown-renderer — <https://github.com/mikepenz/multiplatform-markdown-renderer> ; jeziellago compose-markdown — <https://github.com/jeziellago/compose-markdown> ; Compose `AnnotatedString` / styled text — <https://developer.android.com/develop/ui/compose/text/style-text> ; Compose links (`LinkAnnotation`) — <https://developer.android.com/develop/ui/compose/text/user-interactions>
- **Spec / reference:** CommonMark (the subset semantics) — <https://spec.commonmark.org/> ; Markdown rendering security best practices — <https://www.markdownlang.com/advanced/security.html>
- **Required validators:**
  - None new beyond the existing Gradle gates. `./gradlew test` (+ Compose UI tests via `createComposeRule`) covers parser/render logic — especially link-safety unit tests asserting that disallowed schemes are not actioned and that raw HTML is not rendered. No new pipeline command. Source: <https://developer.android.com/develop/ui/compose/testing>
- **Idioms and constraints** (each item: rule + source URL):
  - **Disable raw HTML for untrusted Markdown.** The documented best practice is to parse with HTML off and allowlist features, rather than render then sanitize: combine "html: false" with an explicit URI-scheme allowlist; "a whitelist approach should be used in favor of blacklisting." Source: <https://www.markdownlang.com/advanced/security.html>
  - **Allowlist link URI schemes; show the real URL; never auto-execute.** Restrict link targets to a safe scheme set (e.g. `https`, `http`, `mailto`) and reject `javascript:` and other schemes; do not navigate on render — only on explicit user tap, ideally with the destination URL visible. Source: <https://www.markdownlang.com/advanced/security.html>
  - **mikepenz/multiplatform-markdown-renderer** (`com.mikepenz:multiplatform-markdown-renderer` + an `-m2`/`-m3` theme module) parses with JetBrains Markdown and renders to Compose; it supports the required subset (bold, italic, code, code blocks, blockquote, links). Remote image loading is **opt-in** — you must explicitly pass an image transformer module (`-coil2`/`-coil3`) — so the default has no remote-fetch surface. Links are surfaced through Compose's own annotation/`UriHandler` mechanism, which the app controls. Source: <https://github.com/mikepenz/multiplatform-markdown-renderer> (README: theme modules, opt-in image transformer).
  - **jeziellago/compose-markdown** (`com.github.jeziellago:compose-markdown`, JitPack) is a Compose wrapper over a `TextView` rendered by **Markwon 4.x** and exposes an `onLinkClicked` callback plus `disableLinkMovementMethod`. To make link handling safe you **must** supply `onLinkClicked` (or a custom Markwon `linkResolver`) — Markwon's default `LinkResolverDef` "tries to start an Activity given the link argument" via an `ACTION_VIEW` Intent, i.e. it auto-opens the URL. Source: <https://github.com/jeziellago/compose-markdown> ; Markwon link resolution <https://noties.io/Markwon/docs/v4/core/configuration.html>
  - **Hand-rolled `AnnotatedString` parser** for exactly the 6 supported elements: build a `buildAnnotatedString { ... }` with `SpanStyle` for bold/italic/inline-code/code-block/blockquote and a `LinkAnnotation.Url`/clickable annotation for links, parsing only the 6 constructs and treating everything else (including `<...>`) as literal text. Smallest dependency and attack surface, fully controllable link policy — but the project owns and must test the parser. Source: <https://developer.android.com/develop/ui/compose/text/style-text> ; links via `LinkAnnotation` <https://developer.android.com/develop/ui/compose/text/user-interactions>
- **Known gotchas:**
  - **jeziellago/compose-markdown hard-wires `HtmlPlugin` and remote image loading into its Markwon builder.** Its renderer calls `.usePlugin(HtmlPlugin.create())` and `.usePlugin(ImagesPlugin.create(...))` (Coil) unconditionally, and `LinkifyPlugin` auto-links bare URLs/emails/phone numbers. For untrusted content that means raw inline/block HTML is parsed and rendered, and image URLs in the message trigger outbound network fetches (a tracking / SSRF-ish leak of "message opened"), with no public toggle to disable them. If this library is chosen, these must be neutralized at the Markwon layer (custom config / fork) — they are not off by default here. Source: dependency + builder in `markdowntext/build.gradle` and `MarkdownRender.kt` <https://github.com/jeziellago/compose-markdown> ; Markwon plugins (HtmlPlugin enables raw-HTML rendering) <https://noties.io/Markwon/docs/v4/core/plugins.html>
  - **Markwon's default link resolver auto-opens links and defaults a scheme to `https`** when none is present — "LinkResolverDef ... tries to start an Activity given the link argument", and it defaults to https when a link lacks scheme information. A safe integration must replace it with a resolver that allowlists schemes, shows the URL, and acts only on explicit tap. Source: <https://noties.io/Markwon/docs/v4/core/configuration.html>
  - **Auto-linkification turns plaintext into tappable links.** Linkify-style passes (Markwon `LinkifyPlugin`, or any autolink) can mint links the sender never wrote (e.g. a string that looks like a URL/phone), widening the link-safety surface — keep autolink off unless explicitly wanted and routed through the same scheme allowlist. Source: <https://github.com/jeziellago/compose-markdown> (LinkifyPlugin wired in) ; general autolink/sanitization caution <https://www.markdownlang.com/advanced/security.html>
- **Last reviewed:** 2026-06-03

---

## Validators wired into pipeline

Every validator listed here must be in the project's `Pipeline` block in `CLAUDE.md`. Pipeline runs them as mandatory gates — green pipeline means every listed validator passed.

| Validator | Command | Gates |
|---|---|---|
| Unit tests | `./gradlew test` | Logic correctness off-device (WebDAV request shaping via MockWebServer, crypto round-trips, Room DAO logic). |
| Android Lint | `./gradlew lint` | Manifest/permission/API-level/security issues; aborts on error by default. |
| Kotlin style (ktlint) | `./gradlew ktlintCheck` | Kotlin formatting/style. (Or `./gradlew detekt` if detekt is chosen — architect's call.) |
| Instrumented tests | `./gradlew connectedAndroidTest` | Device-backed paths: lazysodium native `.so` ABI loading (symmetric AEAD/Argon2id **and** public-key `crypto_box` / `crypto_box_seal` / `crypto_sign` / `crypto_generichash` paths), Android Keystore wrap/unwrap (incl. identity secret key), Room migrations. Requires emulator/device in CI. |

> Detekt (`./gradlew detekt`) is listed as an alternative/complement to ktlint; if the architect selects detekt, swap or add the row. Both are documented validators.

---

## Integration contracts

For each external system the project integrates with — what local artifact carries the contract, how it gets delivered to the external system, and which tool validates the contract end-to-end.

| External system | Local artifact in repo | Delivery mechanism | Validator |
|---|---|---|---|
| WebDAV disk (Yandex.Disk / Nextcloud) — message transport | On-disk protocol layout spec (folder/file naming, ciphertext envelope format, ETag-conditional write rules) — to live at `docs/protocol/webdav-layout.md` (TBD by architect; not yet created) | App writes files at runtime via PROPFIND/MKCOL/PUT/GET/DELETE over OkHttp; no install-time delivery | `./gradlew test` with MockWebServer asserting exact verbs/headers (Depth, If-Match/ETag) + an integration smoke test against a real Nextcloud/Yandex account proving PUT/PROPFIND/GET round-trips and 429 back-off |
| Android Keystore — device key store | Keystore key-alias + spec constants in app source (no separate artifact file) | Keys generated at runtime via `KeyGenParameterSpec` | `./gradlew connectedAndroidTest` proving generate/wrap/unwrap on-device |

>
> The **public identity directory** introduced by the X25519 identity feature is part of this same WebDAV envelope contract: each directory entry carries a member's **public** Ed25519 (and/or X25519) identity key and is **Ed25519-signed** (verify with `crypto_sign_verify_detached`, reject on -1). Only public keys and signatures cross to the disk — identity **secret** keys stay Keystore-wrapped device-local (see *Android Keystore* component). Sealed-box rotation payloads (one `crypto_box_seal` per remaining member's public key) also live on the disk and must be specified in `docs/protocol/webdav-layout.md`. See the *Crypto — Public-key primitives* sub-section.
> The WebDAV on-disk protocol layout document does **not yet exist** — it is the load-bearing contract for interoperability and must be authored before transport coding. `pm-plan-checker` should block any transport feature whose plan lacks a reference to this artifact.
>
> The **compression codec is part of this envelope contract**, not a separate external system: the on-disk envelope must record which codec compressed the plaintext (or "none") so the reader can inflate before the recipient cannot. Compress-then-encrypt ordering and the codec identifier belong in `docs/protocol/webdav-layout.md`. See the *Traffic compression* component for the ordering invariant and the CRIME/BREACH and untrusted-decompression gotchas. Markdown rendering adds **no** integration contract — it is local UI over already-received message text.

---

## How to extend this document

Only `pm-stack-researcher` edits this file. Other agents read it. If `pm-coder` or `pm-plan-checker` notices a missing rule or stale entry, they surface it to the orchestrator — orchestrator spawns `pm-stack-researcher` to update.

Each rule must cite a source URL. Unsourced claims do not belong here — they are guesses dressed as docs.
