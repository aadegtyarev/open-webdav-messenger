# User Journeys

How users interact with the product — step by step. Written for humans and read by agents before planning any feature.

One journey per key user role. Focus on decision points and what can go wrong — not UI details.

> **Note on the current state (updated 2026-06-07 — the chat surface now exists).** The sync engine is the product's first end-to-end behavior: it sends, receives in the background, catches up after offline, and keeps full history on the device. As of the `ui-chat-surface` feature the **visible chat surface** (the screen where a person types and reads, plus the onboarding screens to create a community or join by invite) is now built — so Journey 1 below is no longer engine-only: it is what a person experiences through the app, reached by the onboarding flow in **Journey 2**. (Some of Journey 1's surrounding affordances — a multi-chat list, a settings screen for the polling interval — are still later slices; the single community chat carried in the invite is what opens today.)

> **Note on the current state — remote private chats (engine only, 2026-06-06).** The engine now supports a second way to start a **private chat**: with someone you already share a cloud disk with (a peer discovered through the shared directory), using **public keys alone** — **no passphrase to agree on and no secret of any kind sent over any channel**. Each side combines their own private key with the other's published public key, so both independently arrive at the **same** chat key, and each chat between the same two people gets its **own** distinct key (one chat's key never unlocks another). Like the journey above, this is a **backend capability with no screen yet** — a person cannot today pick a peer and start such a chat from the app; it is exercised by the engine and its tests. What it enables, once the chat-creation / contact UI feature lands: starting a private conversation with a fellow disk-sharer without ever having to exchange a password out-of-band. (Verifying that a discovered peer is really the person you think — the safety-number check — is part of that later UI feature.)

---

## Journey 1: Chat member — send a message and stay caught up

**Entry context:** Two or more people already share a private chat — they agreed on a chat passphrase out-of-band and each pointed the app at the same cloud disk. One of them wants to say something to the others and, over the day, keep up with what everyone else says, including while their phone is in their pocket.

| Step | What the user does | What they expect | What can go wrong |
|---|---|---|---|
| 1. | Sends a message into the chat. | The message goes out once; everyone else in the chat will receive exactly that message, and the sender's own copy is kept on the device immediately. | The disk is unreachable or temporarily rate-limited; the app keeps the message and keeps trying — it is never silently lost, and a retry never produces a doubled message. |
| 2. | Puts the phone away; the app keeps checking for new messages on its own. | New messages from others arrive on their own within about a quarter-hour while the app is in the background — the person does not have to open anything. | The phone is in a deep battery-saving sleep, so a check is skipped or delayed; the app simply catches up on the next check — nothing within the kept-history period is missed. |
| 3. | Opens the app after a while. | Everything received since last time is already there, in the order things were said, with no duplicates — even if the same message happened to be picked up across two separate checks. | A reply or reaction arrives before the message it points at; it is still shown (against an as-yet-unknown original) rather than being dropped or causing an error. |
| 4. | Comes back after being offline longer than one check cycle (e.g. a day away with no signal). | On reconnect the app pulls in everything missed during the absence and shows it in order — the catch-up is automatic. | The person was away **longer than the period messages are kept on the disk**: the oldest missed messages are no longer recoverable from the disk. The app shows what is still within that window; what scrolled out of it before they returned is gone from the disk (it still lives on the devices that already had it). This is an honest, documented limit — see the retention behavior referenced below. |
| 5. | Reads back over earlier conversation, possibly with no connection at all. | Full history is available instantly and offline — scrolling old messages never waits on the network and never depends on the disk. | None at the user level: local history is the device's own and is not bounded by the disk's keep-period. |

**Drop-off points:**

- **Step 2 — "nothing is happening."** Background delivery is roughly quarter-hourly, not instant. Someone expecting a message to land the second the other person hit send may think it is broken. Faster delivery is available via the opt-in `FastPollService` foreground mode with a persistent notification (see `docs/architecture.md` decision #6).
- **Step 4 — "where did the old messages go?"** A person returning from a long absence may expect *everything* to be waiting. The disk only keeps a recent window; messages older than that, which no device of theirs ever held, cannot be reconstructed. This is the most likely source of confusion and must be surfaced honestly when the chat surface exists.

**Invariants:** stated at the human level only; the format/taxonomy/retention rules live once in `docs/architecture.md`.

- By step 1: the sender and recipients must already share the chat passphrase and point at the same disk (membership and key are arranged out-of-band; this journey does not manage them).
- By step 2: the app must be installed and allowed to run background checks; delivery is best-effort within the platform's quarter-hour floor, not guaranteed instant.
- By step 3: messages appear in conversation order and without duplicates regardless of how many checks it took to receive them.
- By step 4: catch-up is complete **only within the disk's keep-period**; older-than-window messages not already on a device are unrecoverable — an accepted limit, not a bug.
- By step 5: local history is independent of the disk and available offline.
- Format / taxonomy / retention / ordering invariants for this journey (what "in order" means, the chat types involved, how long messages stay on the disk, and that a forged or unreadable file is silently skipped): see `docs/architecture.md` `## Behavioral contract (taxonomies & invariants)`.

---

## Journey 2: Community owner — connect a disk, create a community, and invite people

**Entry context:** A person who has a cloud disk (Yandex.Disk / Nextcloud / any WebDAV share) wants to start a small private group chat on it. They open the app for the first time and choose "create a community / I host the disk". They will be the only one who ever types the disk details; everyone else they bring in will join by an invite.

| Step | What the user does | What they expect | What can go wrong |
|---|---|---|---|
| 1. | Chooses "create a community", then enters the disk address, login, app-password, the chat folder, and a community name. | The app sets everything up in one step and drops them straight into the (empty) community chat — there is no separate "create a chat" step; the community *is* its always-on chat. The app quietly creates their on-device identity and a fresh random chat key behind the scenes — they are never asked anything about keys. | They enter a non-HTTPS (`http://`) disk address: the app refuses with a plain message and saves nothing, because the disk password must never travel in clear text. The disk details are wrong or unreachable: that surfaces as a plain connection error later, not a crash. |
| 2. | Taps "invite" to bring someone in. | The app shows a single invite as **both** a copyable string **and** a QR code; the owner sends the string or lets the other person scan the QR. A clear warning is shown: anyone who gets this invite can read and write this chat and use the disk — share it only with people you trust. | The owner shares the invite over an untrusted channel (a public post, a screenshot left lying around): anyone who sees it is in, and there is no way to revoke that one invite — this is why the warning is prominent. |
| 3. | Reads and sends in the community chat (continues into Journey 1). | From here the owner is just a chat member — sending, receiving in the background, and catching up exactly as Journey 1 describes. | Same as Journey 1 (disk unreachable on send → kept and retried, no duplicates; background delivery is roughly quarter-hourly, not instant). |

**Drop-off points:**

- **Step 1 — "why won't it accept my disk?"** The most likely stumble is a non-HTTPS address (refused on purpose) or a wrong app-password. The refusal is deliberate and must read as a safety feature, not a bug.
- **Step 2 — over-sharing the invite.** Because the invite is a bearer token (whoever holds it is in, with no per-invite revocation), the owner may not realise how sensitive it is. The on-screen warning is the only guard; it must stay prominent.

**Invariants:** stated at the human level only; the format/security rules live once in `docs/architecture.md`.

- By step 1: only the owner ever enters disk credentials; a non-HTTPS disk address is refused before anything is saved; the disk password and the chat key are stored only on the device (hardware-wrapped) and never written to the disk or shown again.
- By step 2: the invite carries everything a member needs (disk access + the chat + its key) and travels **out-of-band only** (copied / scanned) — it is never placed on the disk; it is a bearer token with no per-invite revocation, so the trust is in the channel it is shared over.
- Security / storage invariants for this journey (what is hardware-wrapped, why the invite is plain-encoded not encrypted, and that nothing secret is ever written to the disk): see `docs/architecture.md` `## Security constraints` and `docs/threat-model.md`.

---

## Journey 3: Joining member — join by an invite and start chatting

**Entry context:** Someone the owner invited opens the app for the first time and chooses "join by invite". They have either the invite string (pasted to them) or the QR code to scan. They never see or type any disk address or password — the invite carries all of that silently.

| Step | What the user does | What they expect | What can go wrong |
|---|---|---|---|
| 1. | Chooses "join by invite", then **pastes the invite string** or **scans the QR with the camera**. | Either way works. If they scan, the app asks for camera permission the first time. The app then configures itself silently and drops them straight into the community chat — they never see or type the disk address, login, password or folder. | They decline the camera permission, or the device has no camera: the **paste field is always available as a fallback**, so they can still join. |
| 2. | (If the invite is bad) sees what happened. | A garbled or incomplete invite — or a scan of some QR that is **not one of our invites at all** (a random poster/product QR, or plain noise) — is rejected with a clear "this invite isn't valid — check it and try again" message. | None at the user level: a bad or foreign invite is always a clean message, never a crash. A well-formed invite whose disk credentials no longer work surfaces a plain connection/read error later, not a crash. |
| 3. | Reads and sends in the community chat (continues into Journey 1). | From here the member is just a chat member — sending, receiving in the background, and catching up exactly as Journey 1 describes; the disk credentials stay hidden the whole time. | Same as Journey 1. |

**Drop-off points:**

- **Step 1 — camera permission confusion.** A member may decline the camera prompt and think joining is now impossible. The paste fallback must be visible and obvious so a denied/absent camera is never a dead end.
- **Step 2 — "is this invite broken or did I do something wrong?"** Because a foreign QR and a corrupted invite both land on the same "invalid invite" message, a member scanning the wrong thing should be gently pointed back to "check it and try again".

**Invariants:** stated at the human level only; the format/security rules live once in `docs/architecture.md`.

- By step 1: the member never sees or enters the disk address, login, password or folder — these stay hidden inside the invite; the camera is optional and the paste path always works as a fallback.
- By step 2: a non-invite QR or a garbled/incomplete invite is always a clean "invalid" message, never a crash or a half-configured state (nothing is saved from a bad invite).
- By step 3: once joined, the member's experience is identical to the owner's — both converge on the same on-device state; only how that state arrived differs.
- Security / storage invariants for this journey (the silent configuration, the never-shown credentials, reject-don't-guess on a bad invite): see `docs/architecture.md` `## Security constraints` and `## Behavioral contract (taxonomies & invariants)`.

---

## Cross-journey interactions

Journeys 2 and 3 are the **two ways into Journey 1** — they differ only in how the device-local state (disk config + chat key + identity) arrives: the owner types it and the app mints a random key; the member receives it all inside the invite. From the moment either lands in the community chat, the two roles are indistinguishable — both send, receive in the background, and catch up identically. Journey 1 (the chat-member journey) is inherently two-sided — every member is both a sender (step 1) and a background receiver (steps 2–4) of everyone else's messages. The interaction point is the **shared chat**: when one member sends, every other member's next background check is what surfaces it. Two members sending at the same time both get through and neither overwrites the other; a member sending while another is mid-check loses nothing — anything not seen this time is picked up on the next. These guarantees are the same append-only and ordering invariants referenced above in `docs/architecture.md` `## Behavioral contract (taxonomies & invariants)`; they are not restated here.
