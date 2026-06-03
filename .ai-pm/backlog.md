# Backlog

Items noted during planning/review, not yet scheduled. The orchestrator records these; `/pm-plan` matches them against new features.

## Join-flow / community model decisions (PM, 2026-06-03)

These shape the invite / directory / community features below.

- **Single host/owner** — the Yandex-disk owner is the community host: issues invites; only the host can rotate the disk app-password. Accepted single point of trust/control.
- **Bearer invites, accepted for MVP** — whoever scans an onboarding invite is a member forever; no per-invite revocation on Topology A. **BUT** design toward *rotate-with-auto-replace* for individual removal (below) — it makes "kick one person" practical.
- **Rotate-with-auto-replace (individual removal mechanism, enabled by X25519):** to remove one member, the host (1) creates a NEW disk app-password (Yandex allows several concurrently), (2) writes the new password to the disk **encrypted per-remaining-member with each member's X25519 public key** (the removed member is skipped), (3) remaining members sync, decrypt their copy with their private key, auto-update their stored credential, (4) host deletes the OLD app-password → the removed member loses future access, the rest never re-onboard. Caveat: the removed member keeps already-read plaintext and had access until rotation completes; only future access is cut. Requires X25519.
- **X25519 pulled INTO near-term scope** (was deferred): per-user identity keypair, public key published + signed entries in the directory, safety-number verification via QR. Unlocks: anti-impersonation in the directory, remote private chats between members already sharing a disk, and the rotate-with-auto-replace mechanism above.
- **Community = a mandatory all-members community chat** + a directory. Onboarding a member = joining the community = landing in the community chat (the always-on group where everyone is). Additional chats (DMs, sub-groups) layer on top.

## Upcoming features (revised order)

- **X25519 identity (NEAR-TERM — pulled forward)** — per-user identity keypair on top of the crypto substrate (done). Publish the public key in the directory; sign directory entries; verify peers via QR safety-numbers. A fourth key source: DH between two members' keys → a symmetric key fed to the existing XChaCha20-Poly1305 AEAD, enabling remote private chats with no secret exchanged over a channel. Also the prerequisite for the rotate-with-auto-replace removal mechanism. Builds on `crypto/`.
- **User/chat directory on the disk** — discovery of users and chats within a community that shares a disk. **Decision (PM, 2026-06-03): the directory is ENCRYPTED with a community key distributed at onboarding (default = encrypted, NOT plaintext).** The community key rides in the onboarding bundle (alongside disk access); it is NEVER the disk app-password (the operator holds that). Caveat to state in the feature: file/folder structure, sizes, timestamps and write activity still leak some metadata to the disk operator even with an encrypted directory; full traffic-metadata hiding (padding/cover-traffic/name randomization) is a separate, larger concern, out of MVP.
- **Invite / onboarding** — a self-contained **string + QR** (no server, no URL — there is no server) carrying disk access (URL + app-password) + chat-id + chat config + (for random-key private chats) the content key + (later) the community directory key. The crypto substrate already exposes random-key generation and raw-key import/export for this. Illustrative wire format drafted (`owdm1:<base64url(gzip(json))>`, ~264 chars, scannable QR).
  - **Two-layer model (PM clarification, 2026-06-03) — design invites against this:** *community membership* (= holding the disk app-password; one disk per community) and *chat membership* (= holding a chat's `chat-id` + key; many chats per disk) are **separate layers**. So there are two invite kinds: a **community-onboarding** invite (disk access only, no chat-id → chats then discovered via the directory) and a **chat-join** invite (chat-id + key, + disk access if not yet a member). Bundling both in one string is a convenience, not a requirement.
  - **Removal semantics:** removing someone from a *chat* = **re-key that chat** (they lose new messages but keep disk access). Removing from the *community* = **rotate the disk app-password** (everyone re-onboards — heavy on Topology A; single-person revocation needs Topology B / Nextcloud, deferred). Under Topology A a chat-removed member still has disk access → can see ciphertext/metadata and can still delete files (the flat-trust limit). True membership/removal hardening (signatures, Topology B) is future.
- **Message-model** — the plaintext envelope field structure (message-id echo, chat-id, reply-to, reaction, body serialization) that lives inside the AEAD ciphertext. The crypto substrate seals/opens opaque bytes; this feature defines the bytes.
- **Compression** — wire DEFLATE into the envelope `codec-id` (currently always 0x00). Compress-then-encrypt, per-message, bounded inflate (zip-bomb guard) — see architecture decision and stack-notes.
- **UI** — Compose chat surface, passphrase entry, the public-chat "not protected" warning, wrong-password feedback.
- **Forward secrecy / double ratchet** — the genuinely-stronger private-chat path (per-message keys); deferred.

## Downstream expectations recorded from review

- **Chat-model must cache the derived chat key in memory** (plan-checker note, crypto review 2026-06-03). The crypto substrate's "no re-derivation per message" relies on the caller holding the `ChatKey` / using `ChatKeyStore.load()` rather than re-running Argon2id (INTERACTIVE = slow) on every send/receive. The chat-model/sync feature owns this caching; do not re-derive per message.
