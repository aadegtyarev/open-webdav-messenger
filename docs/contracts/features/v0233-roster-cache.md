# v0.23.3 Verified roster cache

> Status: Complete — 2026-10-08

## Goal

After one successful verified roster load, reopening any General, group, or DM chat is Ready immediately from a safe durable cache. WebDAV verification still refreshes asynchronously on every open. Current readiness guards and durable-envelope retry semantics remain unchanged.

## Scenarios and behavior

- Cache identity is scoped to the exact community ID and chat ID and bound to cryptographic provenance sufficient to reject reuse after a community/chat key or account identity changes.
- A valid cached verified roster makes the exact active runtime Ready and Send-enabled before the suspended WebDAV reader returns; refresh proceeds asynchronously and silently. While cached Ready, show no spinner, placeholder, status, or warning; keep the chat visually normal and Send enabled.
- First open without valid cache remains Loading with the top spinner and Send disabled. The disabled message input uses a concise localized equivalent of `Reading participants from server…` instead of the generic message placeholder. The spinner/status control is an accessible, at-least-48dp target; tapping it shows a brief localized explanation equivalent to `Reading participants from server. Sending will be available when complete.` A fresh verified result commits only while its captured request token, selection revision, exact runtime graph, and cache generation remain current. The guarded durable replacement precedes runtime apply; a failed replacement preserves the old cache but may still apply the verified result to the current runtime.
- Silent refresh failure with a valid cache preserves Ready with no foreground error; retry later. With no valid cache, readiness becomes Unavailable with Retry and an appropriate visible status.
- Cache survives process death and contains only public identities and verified recipient metadata—never plaintext, credentials, or private keys. Schema, size, and entry validation are strictly bounded; corruption or invalid data fails closed.
- Invalidate/remove cache on community/chat key change, membership deletion, chat removal, account restore/replacement, or ambiguous identity/provenance. Credential-only rotation may retain cache only when community and chat cryptographic identity is unchanged.
- Async results begun before invalidation cannot repopulate the cache. Sends continue to use one exact Ready verified-recipient snapshot for the persisted retryable envelope and immediate fan-out; existing runtime/request/current-context guards remain authoritative.

## Non-goals

No trust in display-name cache; no permanent bypass of WebDAV verification; no protocol change; no real-time membership guarantee; no UI redesign.

## Affected surfaces

- [Chat surface](../surfaces/chat-surface.md)
- [Background delivery](../surfaces/background-delivery.md)

## Interfaces and constraints

Preserve exact community/chat and cryptographic/account provenance through cache lookup, refresh commit, and invalidation. Cached application verifies the lookup generation while holding the cache lock and applies only to the exact current runtime. A General continuation carries its original request token, selection revision, and installed graph; only the named startup/restore refresh entry point issues an independent token. Cache reads are local and bounded; WebDAV refresh is always asynchronous. Cache data is non-secret and must be validated before use. Existing request token, runtime graph, selection, and account-generation guards apply to both cache and network results.

## Acceptance criteria

1. After one verified load, reopening the same chat is Ready and Send-enabled before a suspended reader completes; General, group, and DM behave uniformly. While this cached background refresh runs, no spinner, placeholder, status, or warning is shown, and failure leaves the chat Ready without foreground error.
2. A verified cache survives process restart; a first open or missing/invalid cache remains Loading until verification completes, with the localized disabled-input placeholder and accessible >=48dp spinner/status target whose tap explains that sending will be available after loading.
3. Community/chat key changes, account replacement/restore, chat removal, and membership deletion prevent reuse; credential-only rotation preserves only unchanged cryptographic identity.
4. Corrupt, malformed, ambiguous, and oversized cache data is rejected. A refresh completing after invalidation or supersession cannot write or apply stale data; a failed atomic replacement leaves the previous encrypted cache intact.
5. A send persists and fans out to the exact same verified snapshot; durable-envelope retry remains unchanged.
6. Existing all-gates, Sol, and release checks pass for v0.23.3.

## Validation

Focused cache, process-restart, invalidation/stale-result, readiness/send-snapshot tests and full Debug/Release gates passed; Sol approved. The Keystore instrumented test was compiled and assembled but not run on a device/emulator.
