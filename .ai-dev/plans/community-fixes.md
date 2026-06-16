# Plan: Four community UI fixes

**Branch:** `feature/group-chats-community-fixes`

## Guarantee
Four UI improvements to the community/group-chat features, each self-contained:
1. CommunityList screen gets Create/Join buttons (onJoin wired to AppRoot)
2. Settings poll interval uses dropdown instead of slider
3. Group chat creation within a community
4. Second community inherits server settings from first

## Scope
- `CommunityListScreen.kt` — Fix 1 (buttons), Fix 3 (group chat list + create dialog)
- `AppRoot.kt` — Fix 1 (wire onJoin)
- `SettingsScreen.kt` — Fix 2 (PersonalPollSection dropdown)
- `AppContainer.kt` — Fix 3 (createGroupChat, openGroupChat, chatsForCommunity methods), Fix 4 (existingConnectionConfig helper)
- `CreateCommunityScreen.kt` — Fix 4 (inherit checkbox, pre-fill logic)

## Progress
- All four fixes implemented
- ktlintCheck: GREEN
- lint: GREEN
- test: GREEN (no existing tests weakened)
