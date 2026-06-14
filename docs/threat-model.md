# Threat model

> The one home for **who attacks this product and what they can take** — kept current; a security-relevant feature plan cites the named actor / boundary / asset it touches.

**Last reviewed:** 2026-06-06

## 1. Actors — who attacks, and why

- **The disk operator (Yandex/Nextcloud) — PRIMARY adversary.** Honest-but-curious untrusted server: holds the WebDAV app-password (A4), sees every on-disk byte, file name, size, timestamp, and all write activity. Motivation: profiling, content access, compelled disclosure. The entire architecture is designed around the assumption this party reads everything it stores.
- **Malicious / curious community insider.** A legitimate member who holds the shared credential and chat keys for their own chats. Can read their own chats by design; may try to forge messages as another member, delete others' messages, or read chats they are not keyed into.
- **Outsider with a leaked invite / credential.** Someone who obtained a bearer invite or the shared app-password out-of-band. Gains whatever the leaked material grants until rotation.
- **Network MITM.** An attacker on the path between device and disk, trying to capture the credential or ciphertext in transit, or tamper with traffic.
- **Casual attacker.** Opportunistic, low-effort: a lost/found device, a shoulder-surf, an automated scan. Looks for plaintext at rest, logged secrets, default-weak config.

## 2. Assets — what is worth taking or breaking

- **A1: Chat message content** — plaintext of private and community chats. All chat content is sealed from the disk operator (SC1/SC2/SC19). Private-chat plaintext also persists device-local in the Room history DB (app-private, never on the WebDAV disk — SC17).
- **A2: Identity secret keys** — per-user Ed25519 signing `sk` and X25519 box `sk`. Compromise enables impersonation, forged rotations, and decryption of DH-keyed chats. Stored Keystore-wrapped, device-local (SC5).
- **A3: Per-chat & community keys & passphrases** — symmetric `ChatKey` (passphrase/random/DH), community key, and user passphrases. Compromise decrypts that chat's content. Stored Keystore-wrapped, never on disk (SC4/SC19).
- **A4: Shared WebDAV disk credential (app-password)** — the one credential per community/chat that authenticates every disk request. Compromise grants full read AND delete of the shared space, but NOT decryption (SC3).
- **A5: Membership / social-graph metadata** — who is in which chat, who talks to whom, when, message volume/timing/structure on disk. Deliberately not fully protected in the MVP.
- **A6: Message integrity & authorship** — guarantee that content is untampered and authored by the claimed member (SC15 signature; content-addressing SC11).
- **A7: Availability of message delivery** — ability to send/receive within retention window. Bounded by no-server, rate-limited, finite-retention design.

## 3. Trust boundaries — where untrusted meets trusted

| Boundary | From → To | Defense |
|---|---|---|
| Device ↔ WebDAV disk | app → untrusted cloud server | AEAD ciphertext only; keys/passphrases never cross (SC1/SC3/SC4/SC5). TLS (SC13). Bounded reads + path-traversal rejection (SC14/SC16). |
| App ↔ Android Keystore | app → device-local secure storage | All secret key material Keystore-wrapped, device-local, never on disk/log (SC4/SC5). |
| App ↔ local Room DB | app → device-local app-private SQLite | Decrypted history app-private, never on disk; carries no keys (SC17). DB not separately app-encrypted at rest — relies on OS device-lock. |
| Member ↔ member | one member → another, over shared AEAD key | Shared key cannot distinguish members; per-message Ed25519 signature authenticates sender (SC15). Content-addressed append-only files resist silent in-place tampering (SC11). |
| Out-of-band invite channel | host → joining member (QR/string, off-disk) | Bearer secret delivered outside the disk; never on disk in the clear. No per-invite revocation in MVP. |

## 4. Abuse cases — the system as designed, turned against the user

- **Spam / harassment through public groups:** a community member creates a "public" group (community-key-sealed) with a provocative title visible to all onboarded members, or posts unwanted content there. Mitigation: the community key gates membership; a harassing member can only be removed by re-keying the community (future rotation). This is a known flat-trust limit — membership is the only barrier.
- **Metadata surveillance through directory polling:** the disk operator observes the `directory/` and `chat-directory/` collection structure, entry count, sizes, and write timing — learning roughly how many members exist, how many groups, and when people publish. Content is AEAD-sealed (SC19), but the collection shape is observable. This is the accepted *Metadata visible to the disk operator* non-goal (A5).
- **Denial-of-service via disk quota exhaustion:** a malicious community member (or an outsider with the leaked credential) writes large garbage files to the shared disk until the quota is full, blocking all legitimate message delivery. Mitigation: under flat trust (SC11) any member can delete any file; quota management is the host's responsibility. The app does not defend quota exhaustion.
- **Impersonation through directory display-name collision:** a member publishes a directory entry with a duplicate display-name and their own valid keys. The signature proves they authored it, but a name does not prove identity. Mitigation: deferred QR safety-number verification (accepted limitation — T20).
- **Chat-descriptor spoofing:** a member publishes a competing chat descriptor for an existing group with a wrong title/access, and with a higher `version-counter` it wins the supersede. The signature proves authorship of that version, NOT chat ownership. Mitigation: accepted limitation; authoritative ownership marker deferred (T23).

## Threats

Each row: threat → affected assets → likelihood/impact → mitigation (SCn IDs only; full rule text in `docs/architecture.md`).

| ID | Threat | Assets | L/I | Mitigation |
|---|---|---|---|---|
| T01 | Disk operator reads private message content | A1 | H/H | SC1, SC3 |
| T02 | Disk operator derives content key from app-password | A1, A3, A4 | H/H | SC3, SC4 |
| T03 | Intra-chat impersonation — member forges as another | A6 | M/H | SC15 |
| T04 | Tampering with on-disk message files (in-place mutation) | A6, A1 | M/M | SC1, SC15, SC11 |
| T05 | Cleartext credential/ciphertext capture by network MITM | A4, A1 | M/H | SC13 |
| T06 | Path traversal via crafted reply-to/target-id or chat-root | A1, A5, A7 | M/M | SC16 |
| T07 | DoS via oversized GET or inflated TLV field-count | A7 | M/M | SC14 |
| T08 | Malicious Markdown (HTML injection, remote-image tracking) | A1, A5 | M/M | SC8 |
| T09 | Compression side-channel (CRIME/BREACH length leak) | A1 | L/M | SC6, SC7 |
| T10 | Sealed-box rotation forgery (sender-unauthenticated) | A2, A3, A4 | M/H | SC12, SC15 |
| T11 | Zip-bomb inflating to exhaust device memory | A7 | L/M | SC7 |
| T12 | Secret material leaking to disk or logs | A2, A3 | M/H | SC4, SC5 |
| T13 | Member reads a chat they are not keyed into | A1 | M/M | SC1, SC11 |
| T14 | User over-shares in a community-wide chat | A1, A5 | M/L | SC2, SC19 |
| T15 | Hand-rolled or misused crypto (nonce reuse, key reuse) | A1, A2, A3 | L/H | SC9, SC1, SC10 |
| T16 | Decrypted history readable at rest on lost/unlocked device | A1 | M/M | SC17 |
| T17 | Longer operator exposure from retention window | A1, A5 | M/M | SC1, SC3 |
| T18 | Change-index leaks per-member activity metadata | A5 | M/L | SC1 |
| T19 | Member over-polls, degrades shared 429 budget | A7 | L/M | (assumption, no SCn) |
| T20 | Directory entry impersonation by display-name | A5, A6 | M/M | SC18 |
| T21 | Community-key compromise ⇒ whole directory readable | A5 | M/M | SC19 |
| T22 | Directory metadata exposure to disk operator | A5 | M/L | SC19, SC1 |
| T23 | Chat-descriptor spoofing / unauthorized supersede | A5, A6 | M/M | SC18, SC20 |
| T24 | Private-group existence/title visible to community | A5 | M/M | SC19, SC1 |
| T25 | Chat-directory metadata exposure to disk operator | A5 | M/L | SC19, SC1 |
| T26 | Native AEAD seal failure crashes publish path | A7 | L/L | SC14 |
| T27 | Secret material committed to source tree / git history | A2, A3, A4 | L/H | SC21 |

Likelihood/Impact: L/M/H.

## 5. Consciously out of scope

- **Metadata visible to the disk operator** — who is in which chat, who writes when, message counts/sizes, file/folder structure. Full metadata hiding (padding, cover traffic) is out of MVP (A5).
- **Message deletion by any member** — under shared credential (SC11) any member can `DELETE` any file. AEAD detects tampering, not deletion.
- **Forward secrecy** — a leaked passphrase/chat key exposes that chat's history. Double-ratchet deferred.
- **Individual invite revocation** — invites are bearer tokens; removing one person requires rotation (future).
- **Directory name↔human binding** — deferred to QR safety-number verification (T20).
- **Community-key per-member revocation** — the community key is a bearer secret; removing one member requires re-keying (T21).
- **Chat-descriptor ownership authority** — descriptors prove authorship, not ownership; ownership marker deferred (T23).
- **Private-group existence/title visible to community** — by design; content key never in directory (T24).
- **Offline longer than retention window** — messages older than the window are unrecoverable from the disk.
- **Cooperative polling-floor enforcement** — honest-client assumption; no enforced rate limit (T19).
- **Physical access to an unlocked device** — OS device-lock is the user's responsibility (T16).
- **Nation-state / endpoint compromise** — compromised OS, malicious keyboard, rooted device, Keystore/CSPRNG subversion is out of scope.

## 6. Currently exposed *(the conclusion)*

**Strongest unmitigated threat:** a malicious community insider with the shared WebDAV credential can **delete every message and every directory/file on the shared disk at any time, with no technical prevention and no recovery mechanism.** Under the flat-trust model (SC11, Topology A), all members share one disk identity — there is no per-author access control, no append-only enforcement at the transport layer, and no witness/replica that could restore deleted content. AEAD and signatures detect tampering of surviving files, but they do not prevent deletion and cannot reconstruct what was deleted. The only defense is social: the members who held local copies of the deleted messages still have them in their Room history (SC17), so content is not lost for active members — but a new member joining after a mass deletion sees an empty chat. This is the cost of the "no server, one credential" design, accepted consciously; deletion resistance via a witnessed/replicated log is a future feature.

**What was never assessed:** `[?]` — side-channel analysis of the polling pattern (can the disk operator infer who a member is chatting with from the timing and size of their `changes/` reads?); `[?]` — formal verification of the append-only/content-addressing integrity under concurrent writers; `[?] — measurable with current transport and sync layers (429 back-off is implemented, window is reserved in webdav-layout §1.4); not yet measured.`
