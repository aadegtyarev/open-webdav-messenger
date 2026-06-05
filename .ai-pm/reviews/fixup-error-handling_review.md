## Trivial fixup: fix/quality-sweep-error-handling

### Condition check

1. **Diff <= 50 lines** — PASS. 15 changed lines total (14 additions, 1 deletion) across 3 source files + 1 state file update.
2. **No user-visible behavior change** — PASS. All three changes convert a crash (unhandled `IllegalArgumentException`) into a typed failure return or a fallback-to-raw-value; the error path was already unreachable in practice for well-formed inputs, and the failure types existed before. No new UI, no new prompt, no behavior change on the happy path.
3. **No `docs/stack-notes.md` touch** — PASS. `git diff main...HEAD -- docs/stack-notes.md` is empty.
4. **No new source files** — PASS. `git show --stat` shows no `new file` entries; only three existing `.kt` files and `state/current.md` are modified.

### Trivial DoD

- [x] Scope respected — exactly the three correctness fixes described; no extra hunks, no refactoring, no opportunistic changes.
- [x] Pipeline green — state entry records "Pipeline green"; no plan/contract files created.
- [x] Docs updated — `state/current.md` carries the fixup record as required.

### Verdict: approve
