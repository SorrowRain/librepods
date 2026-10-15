# Heart Rate Release Checkpoint 2026-08-10 (formal sr-2 release built)

- Branch: `android/airpods-pro3-heart-rate`
- HEAD: `2b27903c2976f2258a790f75185de8d8f6cf7cbc` (upstream baseline `a573241ffac9766031b7c61b676ade8c338bc019`)
- Release: **`1.0.2-sr-2` / versionCode 78** (app + module). Second formal fork release (first was `1.0.2-hyperos.1` / 76).
- Worktree: uncommitted changes (all heart-rate WIP + 6 hardening fixes + pre-release review fixes). **Nothing committed yet.**

## What this release contains (vs upstream 1.0.2)

1. **Heart-rate monitoring** (AirPods Pro 3 / RTBuddy): decoder, session state machine, runtime coordinator, transport, Health Connect export, UI. Auto-retry with exponential backoff (3s→15s cap), START_SEQUENCE 8s watchdog, retry-count surfaced in UI.
2. **Audio guard hardening** (from mainline): GuardHealth now detects `PREFERRED_ROUTE_MISSING` (silent-stall symptom) and surfaces it in the notification; AACP INFORMATION-frame bounds guard + parser crash fix; overlay VideoView MediaPlayer release + visibility restore.
3. **Privacy**: backup/data-extraction rules now exclude `settings` (proximity keys IRK/ENC_KEY, local MAC, serials) and `packet_logs` (raw AACP hex) from cloud backup + device transfer.
4. **CI**: nightly changelog/Discord links de-hardcoded to `$GITHUB_REPOSITORY`.
5. **Premium-lapse cleanup**: `conversational_awareness_both_pods_only` pref cleared when entitlement lapses.
6. **Health Connect**: dropped-measurements counter + UI warning (backpressure no longer silently loses data).

## Pre-release review (workflow, 12 agents) — all confirmed findings fixed

- MEDIUM: `videoView.stopPlayback()` inside OnErrorListener threw on Error-state player → wrapped in try/catch (IslandWindow + PopupWindow).
- LOW: auto-retry backoff counter reset at CALIBRATING (partial warmup then stall = permanent 3s hammer) → now resets only on LIVE or a fresh acquisition (`lastAutoRetryAcquisition`).
- LOW: `PREFERRED_ROUTE_MISSING` never reached the foreground notification → DEGRADED branch now shows the reason; PROBING included when the reason is present.
- LOW: malformed-but-passing INFORMATION frame committed as device identity (empty modelNumber matched first model) → frames with empty modelNumber are now dropped before commit.
- Rejected (documented, not release risks): Play-flavor premium-lapse race (FOSS artifact unaffected); AirpodsModel prefs are Xiaomi's own data; AACp test assertion scope intentional; socket-write block is a documented accepted tradeoff.

## Tests

- `./gradlew testFossDebugUnitTest` — **all green** (incl. new GuardHealth PREFERRED_ROUTE_MISSING tests, AacpInformationParserTest, HeartRateRuntimeCoordinator backoff tests, HeartRateUiState attempt tests).
- SDK: shared Android SDK installed at `~/Library/Android/sdk` (platforms;android-37.0, build-tools 36.1/37.0, ndk 30.0.14904198, cmake 3.22.1, platform-tools). Both `beidaoc-librepods/android/local.properties` and `kdeconnect-xposed-workspace/kdeconnect-clipboard-xposed/local.properties` now point at it (the old per-project `.android-sdk` was deleted to free space).

## Release artifacts (from `packageReleaseArtifacts`)

- Written to repo-root `release/` (git-ignored): `LibrePods-FOSS-v1.0.2-sr-2-release.apk`, `-debug.apk`, `LibrePods-FOSS-v1.0.2-sr-2-release.zip`, `-debug.zip`, play `.aab`.
- Signed with the local RELEASE keystore (4 `RELEASE_*` keys present in `android/local.properties`); version matches `module.prop` (v1.0.2-sr-2 / 78) so the APK inside the ZIP matches the standalone APK.

## Next steps

1. User confirms phone is reachable, then install module ZIP + data APK, reboot, verify heart rate + retry behavior + audio guard.
2. On-device: short BT drop/reconnect (<3s) auto-recovers without grabbing remote audio; CA both-pods-only toggle; AACP persistent-config replay after reconnect; popup/island video plays.
3. After device validation: commit the staged logical change (specific files, no `git add -A`), tag `v1.0.2-sr-2`, publish GitHub release manually.
4. Consider updating `update_nonpatch.json` (still points at upstream v0.2.6 URLs) when next touching release tooling.
