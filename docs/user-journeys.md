# User Journeys

How users interact with the product — step by step. Written for humans and read by agents before planning any feature.

One journey per key user role. Focus on decision points and what can go wrong — not UI details.

> **Note on the current state.** The sync engine is the product's first end-to-end behavior: it sends, receives in the background, catches up after offline, and keeps full history on the device. The visible chat surface (the screen where a person types and reads) is a **later feature** — so today this journey is exercised by the engine and its tests, not by a screen a person taps. The journey below describes the user-observable behavior the engine now supports; once the chat surface lands, this is exactly what the person will experience through it.

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

- **Step 2 — "nothing is happening."** Background delivery is roughly quarter-hourly, not instant. Someone expecting a message to land the second the other person hit send may think it is broken. (Faster, near-instant delivery is a deliberately deferred mode that needs a visible always-on indicator — see `docs/architecture.md` decision #6.)
- **Step 4 — "where did the old messages go?"** A person returning from a long absence may expect *everything* to be waiting. The disk only keeps a recent window; messages older than that, which no device of theirs ever held, cannot be reconstructed. This is the most likely source of confusion and must be surfaced honestly when the chat surface exists.

**Invariants:** stated at the human level only; the format/taxonomy/retention rules live once in `docs/architecture.md`.

- By step 1: the sender and recipients must already share the chat passphrase and point at the same disk (membership and key are arranged out-of-band; this journey does not manage them).
- By step 2: the app must be installed and allowed to run background checks; delivery is best-effort within the platform's quarter-hour floor, not guaranteed instant.
- By step 3: messages appear in conversation order and without duplicates regardless of how many checks it took to receive them.
- By step 4: catch-up is complete **only within the disk's keep-period**; older-than-window messages not already on a device are unrecoverable — an accepted limit, not a bug.
- By step 5: local history is independent of the disk and available offline.
- Format / taxonomy / retention / ordering invariants for this journey (what "in order" means, the chat types involved, how long messages stay on the disk, and that a forged or unreadable file is silently skipped): see `docs/architecture.md` `## Behavioral contract (taxonomies & invariants)`.

---

## Cross-journey interactions

The single journey above is inherently two-sided — every member is both a sender (step 1) and a background receiver (steps 2–4) of everyone else's messages. The interaction point is the **shared chat**: when one member sends, every other member's next background check is what surfaces it. Two members sending at the same time both get through and neither overwrites the other; a member sending while another is mid-check loses nothing — anything not seen this time is picked up on the next. These guarantees are the same append-only and ordering invariants referenced above in `docs/architecture.md` `## Behavioral contract (taxonomies & invariants)`; they are not restated here.
