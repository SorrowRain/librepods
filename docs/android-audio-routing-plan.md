# Android Audio Routing Plan

## Goal

Normal media and video may deliberately move AirPods to the phone. Charging sounds,
Telegram send sounds, and notifications must not interrupt audio playing on another host.

Calls remain controlled by the existing call settings.

## Confirmed Root Causes

The July 20 HyperOS capture separates two independent problems:

1. Telegram and charging sounds use `USAGE_ASSISTANCE_SONIFICATION`. LibrePods correctly
   classified them as ineligible and did not send an ownership request.
2. HyperOS places media and sonification in the same media product strategy, so an excluded
   sound can still open an A2DP stream and make AirPods switch independently of LibrePods.
3. An inbound `ownership=true` is only control ownership. Captures show it followed by a remote
   audio source, so it is not proof that local A2DP is ready.
4. During a local takeover, the old callback released ownership again as soon as it observed the
   still-remote source. That raced the handoff and contributed to pause/play loops.

## Two Separate Policies

The application exposes two policies with opposite meanings. They must not share preferences.

### Automatic takeover allowlist

An enabled category may request AirPods ownership after 300 ms of continuous playback. The
default is `USAGE_MEDIA` only. Calls and ringtones stay outside this policy.

### Keep on phone

An enabled category is rendered to the built-in phone speaker and does not open an AirPods A2DP
stream. The defaults are Android notification usages and `USAGE_ASSISTANCE_SONIFICATION`.
Media and game usages cannot be selected for this policy.

The implementation uses one process-owned dynamic audio policy:

```text
RULE_MATCH_ATTRIBUTE_USAGE
+ ROUTE_FLAG_RENDER
+ TYPE_BUILTIN_SPEAKER
```

`LOOP_BACK_RENDER` is intentionally not used because it copies playback instead of removing the
original A2DP route. The policy is active only while the AirPods AACP control channel is present.
It is unregistered when the service stops or the channel disconnects.

The API is hidden/System API. The APK requests `MODIFY_AUDIO_ROUTING`; the matching root module
installs LibrePods as a privileged app and grants that permission. No `system_server` Xposed scope
is added. If HyperOS still rejects registration, logs must prove that before any system hook is
considered.

## Takeover Completion Gate

LibrePods pauses local media before requesting a handoff. It resumes only when all three signals
belong to the current attempt:

```text
confirmed ownership = local
+ AACP audio source = local phone
+ target AirPods A2DP = connected
```

Remote source reports no longer release ownership while that local attempt is open. A stale
playback cycle cannot execute a later takeover. A timeout cancels the attempt and leaves the media
paused instead of resuming it through the phone speaker.

## Diagnostics

Routing debug mode is off by default. While off, event payloads are not constructed and no routing
file is opened. While enabled, JSONL events include:

- callback and current playback snapshots, category decisions, player state when available
- debounce and playback-cycle identifiers
- physical AACP sends and temporally correlated inbound ownership/source state
- A2DP connection/playback observations and connection requests
- dynamic AudioPolicy registration state and failures
- settings, hook, socket, ownership, source, A2DP, and playback baseline on enable
- a user-inserted time marker

Logs exclude raw packets, MAC addresses, serials, notification text, media titles, and keys. They
rotate in 1 MiB segments with an approximately 8 MiB total cap and can be exported as ZIP.

## Automated Verification

Run:

```bash
cd android
./gradlew :app:testFossDebugUnitTest :app:assembleFossDebug :app:zipDebugModule
```

Unit tests cover category mapping, fail-closed behavior, phone-speaker defaults, and the three-part
route gate. Build validation must also inspect the APK manifest and the ZIP privileged-permission
allowlist.

## Required Device Verification

1. Reinstall the new root ZIP, reboot, then install/start the matching APK.
2. Enable routing debug mode and verify a `phone_sound_policy` event reports `registered`.
3. Keep Mac audio playing. Send a Telegram message, plug/unplug charging, lock/unlock, and receive
   notifications. The sounds must stay on the phone; there must be no ownership request.
4. Start Android music, video, and a podcast. Each playback edge may send at most one ownership
   request and must resume only after the route gate becomes `READY`.
5. Stop media within 300 ms. No request may be sent.
6. Let Mac take AirPods back, retry Android playback, and test a failed handoff. There must be no
   repeated automatic pause/play loop and no unexpected phone-speaker playback.
7. Disable each policy and repeat representative cases to prove its preference is live.
8. Kill and restart LibrePods. `dumpsys media.audio_policy` must show no leaked or duplicate mix.
9. Exercise incoming calls, alarms, ear detection, and manual move-back to detect regressions.

The fix is complete only after HyperOS confirms that the render mix registers and the original
Telegram, charging, notification, and cross-device media scenarios pass on the device.
