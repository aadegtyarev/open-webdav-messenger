# Account recovery surface

## Boundary

This surface covers encrypted export, validated restore, membership reconstruction, and activation of the restored account runtime.

## Consumers

Users transferring an account to a replacement device; `ExportManager`, `RestoreManager`, Keystore-backed stores, registries, and the app runtime consume the same backup boundary.

## Observable behavior

- Current exports use payload version 2 and include every registered community's connection configuration, anchor chat, chat registry, community key when present, all chat keys, identity, and active community selection.
- Restore is available from the first-launch Start screen and Settings. It serializes against polls, sends, credential rotation, onboarding, and other account mutations. Successful restore replaces local account state, rebuilds the selected runtime before success is reported, and returns Settings to the Chats list.
- Legacy v1 exports lack membership metadata. They are accepted only for an empty target with exactly one configured community key and one chat key, which are explicitly mapped to the default community; incompatible or ambiguous targets are rejected before writes.
- Restore validates the complete payload, every chat-key identifier (including keys not referenced by registries), binary keys, identity, WebDAV root/path, bounded community/chat identifiers, registries, and active-community reference before writing. Its local snapshot must successfully unwrap every enumerated community/chat key and stored connection; unreadable encrypted state aborts before replacement rather than being treated as absent. Store failures attempt every independent rollback action and report whether restoration completed. Present config and identity stores are overwritten directly, never cleared first. This remains best-effort rollback, not crash-atomicity across Android Keystore and files.
- Serialized plaintext is limited to 4 MiB and the account-registry section to 1 MiB; import and export enforce the same bounds. Oversized export returns a typed failure.

## Security and privacy

Export contents include disk credentials and identity/community/chat secret keys. The entire payload is password-encrypted before sharing; no secret is logged. Users must protect both the encrypted export and its password. A compromised password enables account takeover. Temporary plaintext is held in memory only during export/restore and key byte buffers are zeroized where supported.

## Failure and limits

Malformed, oversized, ambiguous legacy, or unsupported payloads are rejected before writes. Chat-key files and their enumeration index use strict replacement/deletion operations; failures propagate into restore rollback. A failed write reports whether rollback succeeded; rollback attempts continue across independent stores, and if any rollback action fails the user is warned not to continue using that local account. Restoring does not recover Room message history from the old device. Device/OS crashes between independent store writes are not guaranteed to be atomic.

## Related contracts

- [Chat surface](chat-surface.md)
- [Background delivery](background-delivery.md)
- [Community settings](community-settings.md)
