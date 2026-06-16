# Plan: Credential rotation with auto-replace

## Guarantee

The host can remove a member from a community by rotating the WebDAV disk credential. Remaining members auto-detect and apply the new credential on their next poll cycle; the excluded member loses access when the host deletes the old Yandex app-password. The rotation payload is Ed25519-signed (SC12) inside a sealed box so only the intended recipient can decrypt it.

## Behaviour

**New:**
- Host can tap "Remove" on a member row in the community list → confirmation dialog → credential form (URL, username, new app-password) → app seals the new credential for each remaining member and writes to `meta/credentials/<memberSignPubHex>` on the WebDAV disk.
- During poll cycle, each remaining member checks `meta/credentials/<mySignPubHex>` before the main poll. If found: decrypt with own X25519 keypair, verify Ed25519 signature, update local `ConnectionConfigStore`, delete the blob from disk. The new credential takes effect on the **next** poll cycle (or app restart).
- The removed member gets no blob and loses access when the host deletes the old app-password (manual step).

**Unchanged:**
- Existing poll cycle, send path, directory, chat feed, settings — all unchanged.
- Non-host members see no UI changes (no remove buttons).

## Scope

**In scope:**
1. `directory/CredentialRotation.kt` — seal/open-per-member with Ed25519 signature (SC12)
2. `AppContainer.kt` — `rotateCredential()` method (host-only write path)
3. `EngineWiring.kt` — pre-poll credential check in `SyncRunner` lambda
4. `CommunityListScreen.kt` — "Remove" button visible to host, confirmation dialog, credential form

**Out of scope:**
- Mid-cycle transport rebuild (new credential takes effect next cycle/app restart)
- Host public key resolution from directory (verifier uses the signer public key embedded in the payload)
- Notification to host that members have applied the credential
- Bulk remove (remove one member at a time)
- Undo / re-invite after removal

## Structural choice

**New `CredentialRotation` class home**: `directory/CredentialRotation.kt` — follows the `DirectoryService` pattern (thin orchestration over crypto primitives). It composes `IdentityCrypto.seal()` / `openSealed()` + `Ed25519.sign()` / `verify()`.

Alternative considered: put `rotateCredential()` directly in `AppContainer` with inline crypto. Rejected — the directory module is the home for community-scoped on-disk operations; a separate class keeps it testable in isolation with existing `DirectoryFakeDisk` patterns.

## Product questions

- **Who is this for**: The community host (from `docs/product.md` §1 — the person who created the community and manages membership). The removed member is someone the host wants to eject.
- **What user pain**: Today there is no way to remove a member short of creating a new community and inviting everyone except the unwanted member. That requires all remaining members to reconfigure their apps.
- **What breaks if we DON'T build it**: A community with a departed or problematic member cannot recover short of full rebuild. The product's "group chat" use case is incomplete.
- **Is this the right bet**: Yes. Rotation is the minimum membership-management capability. Alternatives (a signed member-list with enforcement) require more infrastructure (consensus, revocation) and are deferred.
- **The cheapest test that would tell us**: A single manual end-to-end test with two devices on the same community — host removes a simulated third member, remaining member's app auto-updates on next poll. No full build needed to validate the concept; the mechanism is simple enough that the risk is low.

## Verification scenario

**Primary integration layer**: Android Compose UI + WorkManager background poll.

1. **Trigger**: Host taps "Remove" on member "Alice" in Community List → confirms → enters new app-password "p4ssw0rd-new".
2. **Action**: Host taps "Rotate" button → app seals new credential for remaining members, writes to disk.
3. **Observable result**: Remaining member "Bob" sees a poll notification (or on next manual poll), and subsequent messages use the new credential. The host deletes old Yandex app-password; Alice's app can no longer sync.

**Automated test**: JVM unit test (`CredentialRotationTest.kt`) exercising seal→open round-trip with real libsodium, and a `DirectoryFakeDisk` integration test exercising write→list→GET→decrypt→delete.

## Security surface

### Attack surface
- **New input**: credential form fields (URL, username, app-password) — validated for non-empty, URL format for baseUrl. The app-password is a bearer credential and is sealed immediately, never persisted plaintext on disk.
- **New endpoint**: `meta/credentials/<memberSignPubHex>` on WebDAV disk — the path segment `<memberSignPubHex>` is hex of the member's Ed25519 signing public key (64 hex chars, SC16-safe alphabet `[0-9a-f]`). No path traversal possible (restricted alphabet).
- **New format**: sealed-box blob — binary, parsed by `openSealed()`, rejected on failure. JSON inside is parsed with length bounds.

### Secrets & credentials
- **New app-password**: flows through `rotateCredential()` in memory → sealed into per-member blobs → written to disk as sealed ciphertext. Wiped from memory after seal. Never logged (ConnectionConfig.toString() redacts it).
- **Host signing secret**: used to sign the credential JSON; copied from Keystore-backed `Identity`, used in-memory, wiped after sign.

### Trust boundaries
- `CredentialRotation.openForMember()` validates the Ed25519 signature BEFORE accepting any credential — SC12 enforcement at `directory/CredentialRotation.kt:openForMember`.
- The credential blob is sealed (X25519 sealed box) — an attacker without the recipient's box secret cannot decrypt.
- An attacker who can write to `meta/credentials/` (flat trust, SC11) could plant a blob for a member, but the Ed25519 signature check in `openForMember()` fails unless the attacker has the host's signing secret.

### Injection & unsafe ops
- No shell, SQL, or path construction from user input. The member ID hex is derived from verified identity keys, not user input.
- JSON parsing uses `org.json.JSONObject` — bounded, no eval.

### Fail-open vs fail-closed
- Every failure path in `openForMember()` returns `null` → credential is ignored, poll continues with old credential → fail-closed.
- A transport failure during `meta/credentials/` check is silently skipped — next poll retries → fail-closed.

### Data & privacy exposure
- Credential JSON contains the new disk credential (URL, username, app-password). It is sealed inside X25519 sealed boxes — only the intended recipient can decrypt.
- The old credential stays in `ConnectionConfigStore` until the new one is successfully applied.

### AuthZ / AuthN
- No new auth paths. The write path (`rotateCredential()`) is callable from `AppContainer` which is process-local; the UI gates it behind `UserSettings.isHost`.
- The read path runs during poll cycle using the existing transport (which implicitly authenticates as the disk identity).

### Supply chain
- No new dependencies. Uses existing `org.json.JSONObject` (Android SDK) and libsodium (lazysodium).

### SC12 — Ed25519 signature on rotation payload
- **Threat**: A malicious disk operator or flat-trust member plants a forged credential blob. Without a signature, the sealed box is sender-unauthenticated (the box opens, but the opener cannot tell WHO sealed it).
- **Mitigation**: `CredentialRotation.sealForMember()` Ed25519-signs the credential JSON with the host's identity key BEFORE sealing. `openForMember()` verifies the signature against the embedded signer public key. If verification fails, the blob is rejected (returns `null`). Code: `directory/CredentialRotation.kt:sealForMember` and `:openForMember`.

## Unfamiliar interface
- **Sealed boxes**: `IdentityCrypto.seal()` / `openSealed()` are already in the codebase and tested. No new crypto surface.
- **Ed25519 detached signatures**: `IdentityCrypto.sign()` / `verify()` already in the codebase. No new crypto surface.
- **org.json.JSONObject**: Standard Android SDK, used elsewhere in the project (e.g., `CommunityMetadata`). No new surface.

## Docs
- Update `docs/protocol/webdav-layout.md` — add `meta/credentials/` to §1 folder layout and a new § for the credential rotation protocol.
- Update `docs/architecture.md` decision list — add decision for credential rotation mechanism.

## Estimate

- **Non-trivial logic**: Yes — the sealed-box + signature round-trip, credential blob JSON format, and the poll-cycle integration.
- **Tests that could break**: The SyncRunner/SyncEngine tests in `EngineWiringTest.kt` and `SyncStackSpecTest.kt` may need adjustment if the SyncRunner lambda signature changes.
- **Unresolved design decisions**: None — the split between seal/open in `CredentialRotation` and the poll-cycle integration is straightforward following existing patterns.
- **Estimate**: **Medium** — 4 files changed/new, 1 test file new, 1 doc update. Follows established patterns.

## Visual form

**UI (CommunityListScreen)**: Existing table/list of members. Each member row gets a trailing "Remove" icon button (visible only when `UserSettings.isHost == true`). Tapping shows a Material3 `AlertDialog` with:
- Title: "Remove [member name]?"
- Body: "They will lose access after you rotate the disk password."
- Actions: "Cancel" / "Remove"
- On "Remove": dialog closes; a second dialog or bottom sheet opens with fields for new URL, username, app-password + "Rotate" button.

**Doc (webdav-layout.md)**: Table showing `meta/credentials/` layout in §1, new § for per-member blob format.

## Progress note

Plan drafted 2026-06-16. Awaiting Operator approval before build.
