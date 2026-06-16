# Plan: Convert poll interval from minutes to seconds

**Guarantee:** All poll interval settings internalized as seconds; user-facing display shows readable units.

## Behaviour
- Internal storage unit changes from minutes → seconds across all layers (SharedPreferences, metadata JSON, scheduling, foreground service).
- User-facing labels show readable strings: "15s", "30s", "1m", "2m", "5m", "10m", "15m", "30m", "60m".
- New member inheritance: when `communityMinPollSeconds` is set, if `pollIntervalSeconds` < floor, auto-adjust up.

## Scope
All 10 listed files + test files that reference renamed symbols. Defaults: 60s (was 15min), max: 3600s (was 60min). POLL_FLOOR_OPTIONS in seconds.

## Files changed (10 source + tests)
See task enumeration — no deviation.

## Display helper
`formatPollInterval(seconds: Int): String` — added to UserSettings.

## New member inheritance
In `communityMinPollSeconds` setter: if `pollIntervalSeconds < value`, auto-set it to `value`.

## Verification scenario
1. Open Settings → Poll floor dropdown shows "15s", "30s", "1m", etc.
2. Slider moves in seconds; label shows "2m" or "120s" appropriately.
3. After poll cycle updates community floor, member's interval auto-adjusted if below floor.
