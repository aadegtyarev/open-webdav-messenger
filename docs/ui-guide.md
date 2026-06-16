# UI Guide

UI conventions for this project. Read by agents before planning or reviewing any feature with a user interface.

**Last reviewed:** 2026-06-16

---

## Interface type

**What kind of UI:** Native Android, Jetpack Compose. Custom chat surface — the project builds its own chat UI (not a library).

**Target devices:** Android phones (primary). Tablet and landscape are not optimized in the MVP.

**Adaptivity decision:**
- Mobile: required — phone-first design, vertical scrolling feed, bottom-anchored input.
- Tablet: not required for MVP.
- Accessibility: screen-reader support (TalkBack) required per Android accessibility baseline; keyboard navigation not required (touch-first).

---

## Design system

**Component library:** Jetpack Compose Material 3 — standard M3 components for sheets, buttons, text fields, dialogs. Custom composables for the chat feed, message bubbles, and chat list.

**Theming:** Material 3 dynamic color (`MaterialTheme.colorScheme`), light + dark via system setting (`isSystemInDarkTheme()`).

**Icons:** Material Icons Extended (`androidx.compose.material:material-icons-extended`).

**Typography:** Material 3 default type scale. Monospace for inline code / code blocks.

---

## Layout principles

- Phone-first: single-column, vertical scroll, no horizontal scroll.
- Chat feed: reverse-order list (newest at bottom), pinned input bar.
- Onboarding screens: single-column centered form, progress indicator during connection setup.
- Sheets for secondary actions (invite generation, settings); dialogs only for destructive confirmations.
- No horizontal scroll on any screen.

---

## Interaction conventions

- **Loading states:** Circular progress indicator for connection setup and disk operations; shimmer placeholder for the chat feed (future).
- **Error display:** Snackbar for transient errors (sync failures, send retries); inline text for validation errors (bad URL, empty fields).
- **Confirmation dialogs:** Only for destructive actions (leave chat, delete local data).
- **Empty states:** "No messages yet" with the chat name and member count in the feed; "Create a community to get started" on first launch.
- **Back navigation:** System back button / gesture returns to the previous screen; back from the chat feed exits the app.

---

## Readability rules

- Max content column width: full screen width (phone).
- Minimum touch target: 48×48dp (Android accessibility baseline).
- Minimum font size: 14sp body, 12sp secondary/timestamp.
- Line height: Material 3 default (1.3–1.5 depending on text style).
- No more than 3 levels of visual hierarchy per screen.
- Message text: left-aligned, user's own messages visually distinguished (color/alignment).

---

## Anti-patterns for this project

- Do not load remote images in message content — no Coil/Glide for message rendering.
- Do not auto-navigate links — links are displayed as text; navigation only on explicit user tap.
- Do not use WebView for message rendering — all content is `AnnotatedString` / Compose text.
- Do not store camera frames — QR scanner decodes in-memory, frames discarded immediately.

---

## Accessibility baseline

- All images have alt text (if any are added).
- Color is never the only way to convey information (message status uses icons + color).
- Form inputs have visible labels (not just placeholders).
- TalkBack descriptions on interactive elements (send button, chat list items, invite QR).
- Touch targets ≥48dp.
