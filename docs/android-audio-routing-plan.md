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
5. Media-key delivery is asynchronous. A PAUSE dispatched during timeout cleanup could arrive
   after the next user-initiated playback edge and pause the new track roughly one second later.
6. HyperOS batches the relevant BLE advertisements about every 5.5 seconds. Inferring a closed
   lid after 2.5 seconds manufactured false close/open episodes and unnecessary reconnect work.

## Two Separate Policies

The application exposes two policies with opposite meanings. They must not share preferences.

### Automatic takeover allowlist

The default allowlist is `USAGE_MEDIA` only. Calls and ringtones stay outside this policy. Media
already routed to the saved AirPods address proceeds immediately. A known non-target route starts
takeover immediately, an unknown media route is re-read after 100 ms, and enabled short-form
categories retain a 300 ms continuous-playback threshold.

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

When playback is confirmed on a known non-target route, LibrePods pauses local media before
requesting a handoff. That guarded attempt becomes ready when both physical route signals belong
to the current attempt:

```text
AACP audio source = local phone
+ target AirPods A2DP = connected
+ if playback was on a known non-target output:
  active A2DP device or exact target playback route = confirmed
```

Remote source reports no longer release ownership while that local attempt is open. A stale
playback cycle cannot execute a later takeover. Each attempt owns at most one outstanding
asynchronous PAUSE. Timeout cleanup never dispatches another PAUSE. If an attempt fails with an
older PAUSE still outstanding, it owns that PAUSE until the resulting stop is observed, then ends
without automatically sending PLAY; the next explicit user playback edge may retry. During a live
attempt, playback restarted after an already-confirmed PAUSE may create one new PAUSE barrier, while
repeated callbacks cannot duplicate it. A missing hidden-API or active-device confirmation ends the
current attempt without adding a cleanup PAUSE.

The ownership bit remains diagnostic because it can arrive late. Before pausing, the no-takeover
fast path is stricter: it also requires either a started playback configuration whose A2DP address
matches the saved AirPods or a target-specific A2DP playing signal. A connected but idle profile
is not enough.

## Connection Ownership

AACP ownership and Android profile connectivity are independent. Remote playback may release
AACP ownership, but it must not disconnect A2DP/HFP or change their persistent connection policy.
Battery packets never drive profile operations. LibrePods keeps both policies allowed, coalesces
BLE-triggered reconnect attempts, retries the control channel once after a transient failure, and
skips `connect()` when a profile is already connected or connecting. First-seen open/in-ear BLE
advertisements also trigger this check. Explicit disconnects are one-time profile requests and do
not set the persistent policy to forbidden. Lid closure is inferred only after eight seconds
without an advertisement, which is longer than the observed HyperOS batching interval.

## Diagnostics

Routing debug mode is off by default. While off, event payloads are not constructed and no routing
file is opened. While enabled, JSONL events include:

- callback and current playback snapshots, category decisions, player state, and target-route match
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

Unit tests cover category mapping, fail-closed behavior, phone-speaker defaults, target-address
matching, route completion, asynchronous PAUSE isolation, repeated playback edges, and batched BLE
lid-state inference. Build validation must also inspect the APK manifest and ZIP permission allowlist.

## Required Device Verification

1. Reinstall the new root ZIP, reboot, then install/start the matching APK.
2. Enable routing debug mode and verify a `phone_sound_policy` event reports `registered`.
3. Keep Mac audio playing. Send a Telegram message, plug/unplug charging, lock/unlock, and receive
   notifications. The sounds must stay on the phone; there must be no ownership request.
4. Start Android music, video, and a podcast. Each playback edge may send at most one ownership
   request and must become eligible only after the route gate becomes `READY`; when the takeover
   pause guard was used, verify that one explicit play press is required instead of an automatic
   delayed `PLAY`.
5. Start and immediately stop media. At most one current-cycle request may be sent and no stale
   callback may resume or pause a later playback cycle.
6. Let Mac take AirPods back, retry Android playback, and test a failed handoff. There must be no
   repeated automatic pause/play loop and no unexpected phone-speaker playback.
7. Disable each policy and repeat representative cases to prove its preference is live.
8. Kill and restart LibrePods. `dumpsys media.audio_policy` must show no leaked or duplicate mix.
9. Exercise incoming calls, alarms, ear detection, and manual move-back to detect regressions.

For ear detection, remove both earbuds and reinsert them after the A2DP profile has dropped. The
resume path must wait for the profile to reconnect and for 1.5 seconds of quiet media before
sending at most one `PLAY`; a profile disconnect must not clear the ownership of the ear-detection
`PAUSE` or schedule a resume against a stale route.

The fix is complete only after HyperOS confirms that the render mix registers and the original
Telegram, charging, notification, and cross-device media scenarios pass on the device.
