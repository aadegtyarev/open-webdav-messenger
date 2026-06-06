# Product Contract: Community chat — connect, invite, and chat

User-facing contract for the first usable chat surface: a community owner connects a cloud disk and creates a community (= its always-on community chat); members join by a string/QR invite that carries everything silently; both read and send text, online and offline.

---

## User value

Open WebDAV Messenger becomes a real, usable app for the first time. The person who has a cloud disk creates a community by pointing the app at their disk and naming it — the community's always-on chat is ready at once. Everyone else joins by getting a single invite (a string or a QR code) and never has to see or type any disk address or password: the invite carries the disk access and the chat key, hidden inside. From then on both sides simply read the conversation and send messages — instantly and offline from local history, with new messages arriving on their own in the background. It is end-to-end encrypted: the disk operator stores only ciphertext.

## Who uses it

Two roles in a small, trusted private group:
- **The community owner** — the person who holds the cloud disk (Yandex.Disk / Nextcloud / any WebDAV). They enter the disk details once and hand out invites.
- **A joining member** — anyone the owner invites. They paste a string or scan a QR and are in; they never handle disk credentials.

## Must work

- The owner can create a community by entering the disk address, login, app-password and folder plus a community name; a non-HTTPS disk address is refused with a plain message.
- Creating the community immediately creates its one always-on community chat (no separate "create chat" step) and switches background sync on.
- The owner can produce an invite as both a copyable string and a QR code, and is clearly warned that anyone holding the invite can read/write the chat and use the disk.
- A member can join by **pasting the invite string** or **scanning the QR with the camera**; if the camera is denied or absent, pasting always works as a fallback.
- On joining, the app configures itself silently from the invite and drops the member into the community chat — without ever showing or asking for the disk address, login, password or folder.
- Both roles see the chat history in conversation order, instantly and offline; new messages arriving in the background appear on their own.
- Both roles can send a text message; the sender's own message appears immediately and reaches other members on their next background check, with no duplicates ever.
- A broken invite, or a scan of something that is not an Open WebDAV Messenger invite, gives a clear "this invite isn't valid" message — never a crash.

## Must not break

- Disk credentials and chat keys are never written to the cloud disk and never logged; they are stored only on the device, hardware-wrapped. (Per `docs/architecture.md` `## Security constraints` SC4/SC13, `## Behavioral contract`.)
- Private-chat content crosses to the disk as ciphertext only; the disk operator never sees plaintext. (SC1.)
- A joining member never sees or has to enter the disk address, login, password or folder — these stay hidden inside the invite.
- Message ordering and no-duplicate / no-loss catch-up across interrupted and background polls stay exactly as the engine already guarantees. (`docs/user-journeys.md` Journey 1; `docs/architecture.md` `## Behavioral contract`.)
- History stays readable offline and independent of what the disk currently holds.
- A message body is shown as literal text in this version — no link is auto-opened, no remote content is auto-loaded, no markup is rendered. (SC8 surface stays closed until the rendering slice.)
- The invite is a bearer token shared out-of-band only; it is never placed on the disk.

## Acceptance checks

- `invite_token_round_trips_owner_to_member` — verifies the invite carries and recovers disk access + chat-id + chat key + community name (Must work: invite carries everything; Must not break: member needs no credentials).
- `invite_decode_rejects_non_owdm_or_malformed_token` — verifies a bad/foreign invite is a clean error (Must work: broken-invite message, no crash).
- `owner_create_community_persists_keystore_wrapped_auto_creates_chat_and_installs_runner` — verifies create-community auto-creates the chat, stores secrets hardware-wrapped, and switches sync on (Must work; Must not break: secrets device-local).
- `owner_connect_cleartext_url_is_refused` — verifies a non-HTTPS disk is refused (Must work; SC13).
- `member_join_from_invite_configures_silently_without_exposing_credentials` — verifies silent config and that credentials are not exposed (Must work; Must not break: member never sees credentials).
- `camera_denied_falls_back_to_paste` — verifies the paste fallback (Must work: paste always works).
- `feed_renders_local_history_in_order`, `new_message_from_poll_appears_in_open_feed`, `send_persists_local_echo_immediately_and_writes_log_once`, `send_then_background_poll_dedups_to_one_row` — verify read/send/ordering/no-duplicate (Must work; Must not break: ordering + no-dup).
- `feed_shows_message_body_as_literal_plain_text` — verifies the body renders literally, nothing auto-actioned (Must not break: SC8 surface closed).

## Out of scope

- Rich message display — formatting, the reaction set, and reply quoting are not shown yet.
- Public/community-wide chats and the "not protected" warning; password-protected chats; chats started from someone's public key.
- Finding people or other chats inside a community (a directory/discovery screen); a list of multiple chats to switch between.
- Settings (changing how often it checks, a faster always-on delivery mode); backing up and restoring access on a new phone; passing community ownership to someone else; controlling or revoking who may invite.
- Updating the app itself from the community disk.

## Last reviewed

2026-06-06 — by orchestrator (PM-validated draft), against commit current main (feature/ui-chat-surface).

## Built/changed by

- [ui-chat-surface](../../docs/features/ui-chat-surface_plan.md)

---

## How this file is used

- **pm-plan** — checks every plan that touches this feature; the plan's scenarios must respect Must work; the plan's "Out of scope" must not silently relax Must not break.
- **pm-coder** — reads before implementing; the implementation must not break any Must-work or Must-not-break item without a plan that explicitly changes the contract.
- **pm-plan-checker** — checks the diff against Must-work + Must-not-break + Acceptance checks; runs Acceptance checks if they are runnable; if a Must item is touched without contract update — blocking.
- **pm-auditor** — flags features with no contract, stale contracts, contracts that drift from observed code behavior.
- **product map** — `docs/product-map.md` inverts these contracts into the PM-facing generated view.
