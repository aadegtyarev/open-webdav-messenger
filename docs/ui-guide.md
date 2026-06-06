# UI Guide

UI conventions for this project. Read by agents before planning or reviewing any feature with a user interface.

> Authored 2026-06-06 by `pm-architect` for the `ui-chat-surface` feature (the first user-facing slice) from `docs/product.md` + `docs/features/ui-chat-surface_plan.md`. Scoped to a thin first slice — it does **not** over-specify components that do not exist yet (rich message rendering, reactions, replies, a chat list, settings, and discovery are later slices). Material 3 + Jetpack Compose idioms only; no web/CSS. Stack idioms with source URLs live in `docs/stack-notes.md` (Jetpack Compose / QR / CAMERA sections) — not duplicated here.

---

## Interface type

**What kind of UI:** Native Android, Jetpack Compose. Custom chat surface (the project builds its own chat UI — `CLAUDE.md` "UI pattern").

**Target devices:** Android phones (phone-first). Android-only — there is no iOS (deliberate; `docs/product.md` "Does NOT yet").

**Adaptivity decision:**
- Mobile (phone): **required** — this is the only target form factor. Design every screen for a single-hand portrait phone first.
- Tablet: **not required** — a deliberate choice for the MVP. Layouts should not *break* on a larger screen (avoid hard-coded pixel widths; let content reflow), but no tablet-specific multi-pane layout is built.
- Accessibility (TalkBack / large fonts): **baseline required** — content descriptions on every actionable/illustrative element, TalkBack-navigable, and **color is never the only signal** (see Accessibility baseline). Full WCAG conformance is not a goal for the MVP; the baseline below is.

Be explicit: phone-only, Android-only is the deliberate scope. Tablet and iOS are out, by choice.

---

## Design system

**Component library:** Material 3 (`androidx.compose.material3`) — standard Compose Material components (`Scaffold`, `TopAppBar`, `TextField`/`OutlinedTextField`, `Button`, `Card`, `Snackbar`). No third-party component kit; no custom design system beyond Material 3 theming.

**Theming:** Material 3 theming with **both light and dark** themes, following the system setting (`isSystemInDarkTheme()`). Use Material 3 color roles (`primary`, `surface`, `error`, `onSurfaceVariant`, …) — never hard-coded hex colors in composables, so both themes and dynamic color stay coherent. A security-relevant warning (the bearer-invite caveat, the "not private" nudge in a later slice) must remain legible in both themes and must not rely on color alone.

**Icons:** Material Symbols / `androidx.compose.material.icons` (the bundled Material icon set). No custom icon font. Every icon that is an action carries a content description.

**Typography:** the Material 3 type scale (`MaterialTheme.typography`) — `bodyLarge` for message text and form input, `titleMedium`/`titleLarge` for screen and chat titles, `labelLarge` for buttons. Do not invent ad-hoc font sizes; pick a scale role. Respect the user's system font-size setting (use `sp`, never `dp`, for text).

---

## Layout principles

- **One primary action per screen, reachable one-handed.** The connect/create form, the join screen, the invite-share screen, and the feed composer each have one obvious primary action (Create / Join / Share / Send).
- **`Scaffold` + `TopAppBar` per screen.** A back-navigable top bar with the screen (or chat/community) title; the composer is a bottom bar on the feed screen.
- **Forms are single-column**, full-width fields with visible labels, comfortable vertical spacing. The owner connect/create form (server URL, username, app-password, folder, community name) is a vertical list of labeled fields, not a dense grid.
- **No horizontal scrolling** on any screen. Long content (a long message, a long invite string) wraps or scrolls vertically; the invite string is shown in a selectable, copyable, wrapping container with a "copy" affordance, never a single overflowing line.
- **The feed is a vertical, conversation-ordered list** (oldest → newest), scrolled to the latest on open, using a `LazyColumn`. New messages arriving from the background poll append on their own (the list observes the Room `Flow` — no manual refresh control).

---

## Interaction conventions

- **Loading states:** any action that touches the network, the KDF, or Keystore (connect/create, join, send) runs off the UI thread and shows a clear in-progress state — a disabled primary button with an inline progress indicator. Never block the UI thread; never freeze the screen during the WebDAV round-trip or key work.
- **Error display:**
  - **Form/validation errors inline** under the offending field — e.g. a non-HTTPS server URL is refused with a plain inline message ("Use an https:// address — your password must travel encrypted"), and nothing is saved.
  - **A broken / foreign invite** (garbled string, or a scanned QR that is not an Open WebDAV Messenger invite) shows a clear, non-technical message on the join screen ("This invite isn't valid — check it and try again"), never a crash and never a stack trace.
  - **A working invite whose credentials no longer reach the disk** surfaces a plain connection/read error, not a crash.
  - **System/background errors** (a failed poll, an unreachable disk on send) are non-blocking: a send while offline is kept and retried (the message stays in the feed), surfaced with a quiet "will retry" indicator rather than an error dialog.
- **Confirmation dialogs:** only for genuinely destructive or security-bearing actions. The MVP's one security-bearing surface is **sharing the invite** — the invite screen must carry a plain, always-visible warning ("Anyone who gets this invite can read and write this chat and use the disk — share it only with people you trust"), not buried behind a dialog. No confirmation on ordinary sends.
- **Empty states:** every list/feed has an explicit empty state with a short description and, where useful, the next action:
  - **Empty feed** (just created/joined): a friendly "No messages yet — say hello" with the composer ready, never a blank screen.
  - **First launch** (no community): the create-vs-join fork, each with a one-line explanation of the role.

---

## Readability rules

These exist because AI-generated UI tends toward information density and poor hierarchy.

- **Minimum touch target: 48×48 dp** for every tappable element (buttons, the copy/share affordance, the scan affordance, the send button) — the Material minimum.
- **Body / message text: `bodyLarge` (~16sp)**; secondary/metadata text no smaller than `bodySmall` (~12sp). Use `sp` so the user's font-size preference is honored; never lock text to `dp`.
- **Line height / spacing:** use the Material type scale's built-in line heights; give message rows and form fields generous vertical padding so the feed and forms are scannable, not cramped.
- **No more than 3 levels of visual hierarchy per screen.** A chat screen is: title bar / message list / composer — three levels, no more.
- **Message text renders as literal plain text in this slice** — Markdown characters show as-is, links are not tappable, nothing is auto-loaded (the SC8 rendering surface stays closed until the rendering slice). Do not introduce styled spans, clickable links, or image loading here.

---

## Anti-patterns for this project

Things to avoid (some are constraint-bound, not just taste):

- **Do not render message bodies as rich text / HTML / tappable links in this slice.** Literal plain text only (SC8 — the untrusted-content rendering surface is deliberately closed until its own slice). No auto-linkification, no remote image loading.
- **Do not show the disk URL, username, app-password, or folder to a joining member** — they are carried inside the invite and must never appear in the join UI or any member-facing screen (contract "Must not break"; `member_join_from_invite_configures_silently_without_exposing_credentials`). Never put a credential into observable UI state.
- **Do not log or echo the invite token, the app-password, or any chat key** — they are bearer/secret material (SC4 / SC21). No "debug" surface that prints them.
- **Do not gate joining on the camera.** The paste field is always present and always works; the scanner is an optional enhancement that degrades silently when the camera is denied or absent (`camera_denied_falls_back_to_paste`).
- **Do not do network / Room / KDF / Keystore work inside a composable.** Compose is side-effect-free and recomposes often/in any order — all I/O and key work belongs in a ViewModel on a background dispatcher; pass results down as state (stack-notes Compose).
- **Do not convey state with color alone** (e.g. an error in red only, a "sent/pending" status in green only) — pair it with an icon, label, or text.
- **No hard-coded hex colors or ad-hoc font sizes** — use Material 3 color roles and the type scale so both light and dark stay coherent.

---

## Accessibility baseline

- **Content descriptions** on every actionable and every informative non-text element (the QR image, the copy/share/scan/send icons). The displayed invite QR is decorative-with-a-purpose — give it a description and ensure the **string form** is always available as a TalkBack-readable, copyable alternative.
- **Color is never the only way to convey information** — warnings, errors, and message status always carry an icon or text label in addition to color.
- **Form inputs have visible labels** (not placeholder-only) — every field on the connect/create and join screens.
- **TalkBack-navigable:** logical focus order on every screen; the primary action is reachable; the feed's messages and the composer are individually focusable.
- **Respect system font scaling** (text in `sp`); the UI must remain usable at larger system font sizes without clipping or overlap.
