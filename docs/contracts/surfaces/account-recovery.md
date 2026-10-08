# Account recovery surface

## Boundary

This surface covers encrypted export, validated restore, membership reconstruction, and activation of the restored account runtime.

## Consumers

Users transferring an account to a replacement device; `ExportManager`, `RestoreManager`, Keystore-backed stores, registries, and the app runtime consume the same backup boundary.

## Observable behavior

- Current exports use payload version 2 and include every registered community's connection configuration, anchor chat, chat registry, community key when present, all chat keys, identity, and active community selection.
- Restore is available from the first-launch Start screen and Settings. Successful restore replaces local account state, rebuilds the selected runtime, and returns to the Chats list.
- Legacy v1 exports remain readable as far as their contents permit. A v1 export lacks community/chat membership metadata; it may restore secrets but cannot always create a usable joined runtime. The UI must not claim account activation when no runtime could be reconstructed.
- Restore decrypts and validates the complete payload, binary keys, identity, URLs, registries, and active-community reference before writing stores. A store failure yields a typed failure and attempts to restore snapshots. This is rollback protection for app-level store failures, not a claim of crash-atomicity across Android Keystore and files.

## Security and privacy

Export contents include disk credentials and identity/community/chat secret keys. The entire payload is password-encrypted before sharing; no secret is logged. Users must protect both the encrypted export and its password. A compromised password enables account takeover. Temporary plaintext is held in memory only during export/restore and key byte buffers are zeroized where supported.

## Failure and limits

Malformed or unsupported payloads are rejected before writes. A failed write reports whether rollback succeeded; if rollback itself fails, the user is warned not to continue using that local account. Restoring does not recover Room message history from the old device. Device/OS crashes between independent store writes are not guaranteed to be atomic.

## Related contracts

- [Chat surface](chat-surface.md)
- [Background delivery](background-delivery.md)
- [Community settings](community-settings.md)
