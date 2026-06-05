## Trivial fixup: fix/quality-sweep-transport-keystore

### Condition check

| Condition | Result | Detail |
|---|---|---|
| Diff ≤ 50 lines | PASS | 37 changed source lines (41 total including state file) |
| No user-visible behavior change | PASS | All three fixes are internal crash-to-typed-error conversions; no API surface, UI, or message content changes |
| No stack-notes.md touch | PASS | `docs/stack-notes.md` not in diff |
| No new source files | PASS | Only two existing source files modified; no new files added |

### Trivial DoD

- [x] Scope respected — fixes match the three described items (207-guard, wrap() IOException, gate() URL parse); no other hunks present
- [x] Pipeline green — state file records "Pipeline green. Done."
- [x] Docs updated — `.ai-pm/state/current.md` updated to reflect completion of fixes #6/#7/#8

### Notes

- The `@Suppress("TooGenericExceptionCaught")` annotation in `KeystoreWrapper.wrap()` is method-level (line 53), not file-level. CLAUDE.md prohibits only file-level lint suppressions; this is compliant.
- The existing `@Suppress("TooGenericExceptionCaught")` on `unwrap()` already set the pattern; `wrap()` now mirrors it consistently.

**Verdict: approve**
