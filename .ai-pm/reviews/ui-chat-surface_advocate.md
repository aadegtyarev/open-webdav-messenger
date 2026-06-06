# ui-chat-surface — product-readiness gate

## Product-readiness gaps

Tier: per-feature
Checklist: `### Foundational product questions` in `workflow/foundational-questions.md`

No gaps — every foundational question has a recorded answer.

(Presence-only notes, in the checklist's fixed order:

1. Value — who is this for / what job: `.ai-pm/contracts/community-chat.md` `## Who uses it` (community owner + joining member, small trusted private group) and `## User value`; plan's two-role framing.
2. Value — why this, not the incumbent: `docs/product.md` `## Why this exists` (no server of its own; removes the need to run a chat server or trust a third-party messenger).
3. Usability — how a user reaches/discovers it: plan Scenarios 1 & 3 (first-launch "create a community / I host the disk" / "join by invite" — paste string or scan QR); contract `## Must work`.
4. Usability — first successful use / zero-to-working: plan Scenarios 1–6 and contract `## User value` + `## Must work` (owner connect → auto-create community chat → invite → read/send; member paste/scan → silent config → land in chat → read/send).
5. Scope boundary — explicit No-Gos: plan `## Out of scope` and contract `## Out of scope` (rich rendering, public/passphrase chats, discovery UI, settings, ownership migration, controlled invites, app self-update).)

## Verdict

clean
