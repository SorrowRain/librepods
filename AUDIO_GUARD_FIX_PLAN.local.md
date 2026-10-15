# Audio Guard Intermittent Steal — Fix Plan (CONVERGED + IMPLEMENTED batch 1)

Status: workflow (7 findings) + opus rounds 1-7 (CONVERGED). Batch 1 IMPLEMENTED, then opus implementation review found 4 bugs, ALL FIXED and re-verified by opus. **READY FOR ON-DEVICE TEST (2026-08-11).**

## Batch 1 implementation — opus review + 4 fixes (opus-verified)

- Bug #1 (two-strike debounce defeated by missing RequestAudioSource): FIXED — first strike now adds RequestAudioSource; debounce window ~1s (acceptable).
- Bug #2 (re-entrancy: BarrierFailed dispatched inside action loop): FIXED — deferred until after the full action batch; brief lease-armed-then-cleared (<1ms) acceptable.
- Bug #3 (graceStartMs not cleared on release): FIXED — cleared on all REMOTE_GRACE exits.
- Bug #4 (PROBING + BarrierFailed abandons probe): FIXED — transient failure stays in PROBING (probe is diagnostic, barrier is safety), permanent goes OFF.
- opus verdict: **READY FOR ON-DEVICE TEST.**
- testFossDebugUnitTest: ALL GREEN.

## On-device verification checklist (next)

1. Two-strike LOCAL takeover works reliably (Mac streaming, phone notification -> stays protected on 1st, releases on 2nd observation).
2. Phone call transfer -> immediate LOCAL (no debounce delay).
3. No stale native lease after a barrier failure (guard degrades, doesn't fake protection).
4. Grace extends across Mac gapless gaps (no premature release while Mac still streams).
5. Barrier permanently unavailable (e.g. root module removed) -> terminal OFF + GuardHealth DEGRADED, no infinite retry.

## Opus round 2 consensus (2026-08-11)

- Design closes F1-F7 with 2 holes patched + 4 clarifications.
- **Decision A: add CONFIRMING_REMOTE + CONFIRMING_LOCAL states** (Option 1). Enum ripple is mechanical, not real cost; reuse of REMOTE_GRACE would need a compound sub-state. Semantic clarity + separate cadence win.
- **Decision B: two batches.** Batch 1 = minimal state-machine fixes closing F1-F7 with existing states + counters (no new states, ~40 lines + Code C barrier fixes + 10 tests). Batch 2 = CONFIRMING architecture, 500ms fast-confirm, user override, localMac-invalid handling, release-ordering refinement.
- **Decision C: defer user manual override to batch 2** (not a bug fix; new UI surface = release risk).
- 2 holes found: (1) CONFIRMING_LOCAL needs 5s absolute timeout → fail-open to LOCAL (emit DisableBarrier+ClearNativeLease); (2) CONFIRMING_LOCAL→LOCAL needs explicit [DisableBarrier, ClearNativeLease].
- 4 clarifications: Code C amendment is a real code change; ACTIVE_UNKNOWN should be treated as UNKNOWN (trigger confirm) not REMOTE; CONFIRMING_REMOTE 5s is absolute-from-entry; CONFIRMING transition action lists must be explicit.
- Batch 1 remaining tradeoff: 2s LOCAL release latency (1000ms cadence × 2) vs 1s with CONFIRMING. Pathological flip-flop every 1s is the only residual failure mode (not real-world).

## CRITICAL CORRECTION (my verification, after round 3)

Opus round 3 claimed "CALL already working: owner() returns LOCAL for CALL, handlers already transition to LOCAL without confirmation." This is **WRONG for the intended semantics**. The existing code:
- `owner()` treats CALL/MEDIA/ACTIVE_UNKNOWN identically (source==local → LOCAL), and
- `AudioSourceObserved` LOCAL branch does NOT distinguish CALL from MEDIA — BOTH immediately releaseIfNeeded → LOCAL.

So the current behavior for phone-MAC+CALL from REMOTE_ACTIVE IS "immediate LOCAL" (which is correct for call handoff), BUT this is the SAME immediate-release path that causes F3 (spurious MEDIA release). The fix must therefore:
- **CALL from phone MAC → immediate LOCAL (no debounce)** — user-initiated call transfer, keep current instant behavior.
- **MEDIA from phone MAC → debounce (2-strike / CONFIRMING_LOCAL)** — fixes F3.
- **ACTIVE_UNKNOWN from phone MAC → debounce too** (uncertain), or treat as REMOTE per round-2 (final: debounce into confirm, since ACTIVE_UNKNOWN means "type byte unrecognized" not "phone confirmed").

This distinction MUST be explicit in both batch 1 (where REMOTE_ACTIVE + LOCAL-owner must check event.type) and batch 2 (transition table already does this for IDLE, must extend to ALL states).

## CONFIRMING transition action lists (opus clarification)

- Enter CONFIRMING_REMOTE from IDLE/LOCAL: [EnableBarrier, RefreshNativeLease, RequestAudioSource].
- Enter CONFIRMING_LOCAL from REMOTE_ACTIVE: [RequestAudioSource] (barrier+lease already active, keep them).
- CONFIRMING_LOCAL 2×LOCAL → LOCAL: [DisableBarrier, ClearNativeLease].
- CONFIRMING_LOCAL 5s timeout (no 2×LOCAL) → LOCAL + [DisableBarrier, ClearNativeLease].
- CONFIRMING_REMOTE 5s timeout (no 2×REMOTE) → IDLE + [DisableBarrier, ClearNativeLease].

## Opus round 3 full blueprint (2026-08-11) — see below sections

Batch 1 = no new states; Batch 2 = CONFIRMING states. Full transition tables, config constants, test impact, and 8-extras assignment in the sections below. This blueprint is the implementation reference.

## Problem

On sr-2, the remote-device audio guard works most of the time but INTERMITTENTLY the phone steals
audio the Mac is playing through shared AirPods. Root cause: the guard drops the speaker barrier
(+ A2DP START lease) on transient/uncertain audio-source reports, opening a 1-5s window where phone
audio routes to AirPods.

## Confirmed findings (workflow, 12 agents, 7 confirmed)

- F1 HIGH: EnableBarrier swallows registration failure (setGuardEnabled never throws; status.active=false), guard still enters REMOTE_ACTIVE, RefreshNativeLease skips A2DP block → up to 1s with no protection while guard reports healthy.
- F2 HIGH: setGuardEnabled(true) early-returns when registeredPolicy != null without re-applying preferred route; after a failed unregister (route cleared, policy stuck) the route is never restored → already-open phone A2DP stays on AirPods.
- F3 MEDIUM: single phone-MAC (LOCAL) report from REMOTE_ACTIVE instantly DisableBarrier+ClearNativeLease → LOCAL 5s. Triggered by lingering CALL report or unrecognized type byte (ACTIVE_UNKNOWN) carrying phone MAC. Asymmetric vs NONE/UNKNOWN which go to grace (keep barrier).
- F4 MEDIUM: REMOTE_GRACE fixed 2.5s phaseEnd, never extended on repeated NONE/UNKNOWN; expiry → IDLE (release). Mac stream gap >2.5s loses protection.
- F5 MEDIUM: NONE in PROBING immediately releases to IDLE (no grace); ~550ms switch window unprotected.
- F6 MEDIUM: detection angle of F3 (fix = debounce LOCAL from REMOTE_ACTIVE).
- F7 MEDIUM: REMOTE_GRACE expiry hard-releases on NONE/UNKNOWN instead of keeping protection.

## Opus consensus (round 1) — verdict

- All F1-F7 CONFIRMED.
- P1/P2/P3 directionally correct but incomplete. Key corrections:
  - Don't throw exceptions; return status synchronously. **Prevent REMOTE_ACTIVE transition until barrier confirmed active** (don't let state drift from reality).
  - **Must exempt phone-MAC + CALL** (immediate LOCAL, user call transfer needs instant handoff). Only grace phone-MAC + MEDIA / ACTIVE_UNKNOWN.
  - ACTIVE_UNKNOWN → treat as REMOTE (keep protecting).
  - Unified CONFIRMING_REMOTE / CONFIRMING_LOCAL states, fail-safe (CONFIRMING keeps barrier active), poll 500ms × 2, bypass for phone-MAC+CALL.
- 8 additional issues opus flagged (see below).

## Proposed design (opus unified architecture)

New states: CONFIRMING_REMOTE, CONFIRMING_LOCAL (added to the enum; barrierEnabled=true for both).

Transitions:
- IDLE/LOCAL + AudioSourceObserved(REMOTE) → CONFIRMING_REMOTE (enable barrier+lease, poll 500ms), 2 consecutive REMOTE → REMOTE_ACTIVE.
- ANY + phone-MAC+CALL → LOCAL immediately (disable barrier+lease).  ← call handoff must be instant
- REMOTE_ACTIVE + phone-MAC+MEDIA/ACTIVE_UNKNOWN → CONFIRMING_LOCAL (KEEP barrier+lease, poll 500ms), 2 consecutive LOCAL → LOCAL (release). ← debounce, fixes F3/F6
- REMOTE_ACTIVE + NONE/UNKNOWN → CONFIRMING_REMOTE (KEEP barrier+lease, EXTEND deadline on repeats), max 5s then IDLE fail-open. ← fixes F4/F7
- CONFIRMING_X observations flip → reset counter, stay in CONFIRMING, keep barrier active.
- PROBING: add timeout (10s) → IDLE fail-open.
- ACTIVE_UNKNOWN always treated as REMOTE.
- Explicit disconnect → IDLE immediately.

Barrier/lease coordination (fixes F1/F2):
- setGuardEnabled returns synchronously with status; AirPodsService checks status.active before transitioning to REMOTE_ACTIVE/CONFIRMING; if false → stay PROBING, retry.
- setGuardEnabled(true): when registeredPolicy != null, re-apply preferred route idempotently; if status.state indicates prior failure, force unregister+re-register.
- RefreshNativeLease only when status.active (already correct in Code D).
- Invariant: guard must never believe it's protected while status.active is false.

## Opus's 8 additional findings to fold in

1. Barrier registration async — need synchronous status or explicit barrier-armed confirmation before arming lease.
2. PROBING no timeout — add 10s fail-open.
3. localMac invalid → treat all sources as REMOTE (safer default).
4. Call priority: bypass confirmation for phone-MAC+CALL from ANY state.
5. Unregister retry + quick re-enable → stale policy; force unregister+re-register when status.state indicates failure.
6. Barrier/lease release ordering — DisableBarrier before ClearNativeLease leaves lease valid up to 7.5s; clear lease atomically/earlier.
7. User manual override (QS tile / notification) to force LOCAL 30s.
8. CONFIRMING polling battery — test no stuck-CONFIRMING state.

## Trade-off decisions for the user

A. Add CONFIRMING states (opus's clean design, ~30 test assertions updated) vs reuse REMOTE_GRACE as the confirm state (smaller diff, fewer test changes, but REMOTE_GRACE semantics already mean "remote confirmed, transient blank" — reusing it for LOCAL-confirm is semantically muddy).

B. Scope: ship all fixes in one release (bigger change, more testing) vs two batches (F1/F2 barrier fixes first as blocking; F3-F7 debounce in a follow-up).

C. User manual override (opus #7): include now (new UI) or defer (bigger surface).

## BATCH 1 — precise implementation (opus round 3, verbatim blueprint)

### 1.1 F3/F6 two-strike LOCAL debounce (RemoteDeviceAudioGuard.kt)
- GuardSnapshot += `consecutiveLocalCount: Int = 0`.
- In REMOTE_ACTIVE, when observedOwner == LOCAL: increment count. If >=2: releaseIfNeeded → LOCAL, deadline now+5000, reset count. If ==1: STAY in REMOTE_ACTIVE (barrier+lease stay), actions [RefreshNativeLease, RequestAudioSource], deadline now+1000, keep count.
- Reset rules: any REMOTE observation → reset count + protectOrRefresh. NONE/UNKNOWN → reset count + enterGrace.
- REMOTE_GRACE + LOCAL: same two-strike, >=2 → releaseIfNeeded → LOCAL.
- **CORRECTION**: only MEDIA/ACTIVE_UNKNOWN observations increment the debounce count. phone-MAC+CALL must go to LOCAL immediately (user call transfer) — do NOT debounce CALL.

### 1.2 F4/F7 grace extension (RemoteDeviceAudioGuard.kt)
- Config: `maxGraceExtensionMs = 5000L`, `maxGraceExtensions = 3`.
- GuardSnapshot += `graceExtensionCount: Int = 0`, `lastObservedOwner: Owner? = null`.
- In Tick when REMOTE_GRACE phaseEnded: if lastObservedOwner in {NONE, UNKNOWN} && graceExtensionCount < maxGraceExtensions → extend phaseEnd = now + min(graceRetry, maxGraceExtensionMs - elapsed), graceExtensionCount++, actions [RefreshNativeLease, RequestAudioSource]. Else hard release → IDLE + [DisableBarrier, ClearNativeLease, RequestAudioSource], reset graceExtensionCount.

### 1.3 F1/F2 barrier drift
- 1.3.1 Code C (PhoneSoundRoutingController.setGuardEnabled(true)): when registeredPolicy != null → applyPreferredSpeakerRoute() idempotently + set status.preferredRouteApplied=true, return. If status.state in {unregistration_failed, registration_cleanup_failed} → force unregisterAudioPolicy (catch) → registeredPolicy=null, status idle/active=false, then re-register normally.
- 1.3.2 Code D (AirPodsService): add `BarrierFailed` event to guard; after an action batch containing EnableBarrier, check status.active; if false → dispatch BarrierFailed → guard handler reverts to PROBING (deadline now+probeTimeout, reset counters) + actions [DisableBarrier, ClearNativeLease, RequestAudioSource].

### 1.4 F5 PROBING NONE stays probing (RemoteDeviceAudioGuard.kt)
- PROBING + NONE: stay in PROBING, deadline = min(current.deadline, now+probeRetry), actions [RequestAudioSource]. (Do NOT release to IDLE.)
- PROBING Tick at deadline: fail-open → IDLE + [DisableBarrier, ClearNativeLease, RequestAudioSource].

### 1.5 Batch 1 config constants
`localDebounceCount = 2`, `maxGraceExtensionMs = 5000`, `maxGraceExtensions = 3`. (probeTimeout=1500 already exists.)

### Batch 1 test additions
Two-strike LOCAL (1 then 2 MEDIA observations), CALL bypass (immediate LOCAL), grace extension (3×), PROBING NONE stays, PROBING timeout→IDLE, BarrierFailed→PROBING. Manual: Mac streaming + phone MEDIA notification → protected until 2nd observation.

## BATCH 2 — CONFIRMING states (opus round 3, verbatim blueprint)

- Enum += CONFIRMING_REMOTE, CONFIRMING_LOCAL. barrierEnabled set += both.
- Config: `confirmPollMs = 500`, `confirmCommitCount = 2`, `confirmTimeoutMs = 5000`.
- GuardSnapshot += `confirmationCount: Int = 0`, `confirmingOwner: Owner? = null`.
- Transition table (all states; IDLE shown in round 3, must extend to all):
  - IDLE/LOCAL + REMOTE → CONFIRMING_REMOTE [EnableBarrier, RefreshNativeLease, RequestAudioSource]; 2×REMOTE → REMOTE_ACTIVE.
  - ANY + phone-MAC+CALL → LOCAL immediately [DisableBarrier, ClearNativeLease, RequestAudioSource].
  - REMOTE_ACTIVE + phone-MAC MEDIA/ACTIVE_UNKNOWN → CONFIRMING_LOCAL [RequestAudioSource] (keep barrier+lease); 2×LOCAL → LOCAL [DisableBarrier, ClearNativeLease]; 5s timeout → LOCAL (fail-open, most recent was phone).
  - REMOTE_ACTIVE + NONE/UNKNOWN → CONFIRMING_REMOTE (keep barrier+lease, EXTEND on repeats, 5s absolute → IDLE).
  - CONFIRMING_X flip (REMOTE↔LOCAL) → reset confirmationCount, stay in CONFIRMING, keep barrier.
  - PROBING timeout (10s) → IDLE fail-open. ACTIVE_UNKNOWN treated as UNKNOWN (uncertain) not REMOTE.
  - Explicit disconnect → IDLE.
- Enum ripple: GuardHealth barrierEnabledState, AirPodsService ~801-806/1190-1191/3536-3540, ~30 test assertions.
- Extras 4/7/8 (localMac-invalid→UNKNOWN, user override QS toggle, battery-charging fallback) → batch 2.

## Opus round 4 final-confirmation result (2026-08-11)

F1-F7 all covered by the corrected plan. BUT two blocking issues found:

### 🟥 HOLE 1 (production blocker): BarrierFailed infinite retry loop (batch 1.3b)
Trace: REMOTE_ACTIVE -> EnableBarrier fails -> status.active=false -> BarrierFailed -> PROBING -> next REMOTE observation -> REMOTE_ACTIVE -> EnableBarrier fails again -> infinite loop. If barrier registration PERMANENTLY fails (MODIFY_AUDIO_ROUTING missing, policy bug), the guard oscillates PROBING<->REMOTE_ACTIVE forever, burning wakelock, spamming logcat, repeatedly writing XposedRemotePref.
FIX:
- GuardSnapshot += `consecutiveBarrierFailures: Int = 0`.
- Increment on BarrierFailed; reset to 0 on a successful EnableBarrier (status.active==true after action batch).
- After 3 consecutive failures -> transition to a terminal degraded state (OFF or a new DEGRADED) + [DisableBarrier, ClearNativeLease], stop retrying.
- AND/OR: setGuardEnabled distinguishes `permission_missing` (permanent) vs transient failure in status.state; when permission_missing, guard goes OFF/INACTIVE and stops dispatching EnableBarrier (surfaced via GuardHealth PERMISSION_MISSING, which already exists).

### 🟨 HOLE 2 (ambiguity): grace extension formula (batch 1.2)
- GuardSnapshot += `graceStartTime: Long?`.
- On REMOTE_GRACE entry: set graceStartTime = now.
- On extension (lastObservedOwner in {NONE,UNKNOWN}, graceExtensionCount < maxGraceExtensions): `phaseEnd = min(now + graceRetry, graceStartTime + maxGraceExtensionMs)`, `deadline = now + graceRetry`, graceExtensionCount++.
- Reset graceStartTime + graceExtensionCount on fresh REMOTE_GRACE entry (not on extension).
- maxGraceExtensionMs = 5000 is CUMULATIVE from grace entry, not per-extension.

### ✅ Confirmed sound (no change needed)
- Two-strike debounce DOES prevent the FIRST phone media sound: the native lease blocks A2DP START in the BT stack before it reaches AirPods, so a healthy lease means no LOCAL observation occurs at all; two-strike guards barrier/lease drift, not first-sound.
- CALL vs MEDIA distinction is clean: AudioSourceObserved already carries type; state machine branches on owner()+event.type. owner() unchanged.
- Batch 2 design sound; defer until batch 1 stabilizes on device.
- Batch 2 ACTIVE_UNKNOWN->UNKNOWN change adds ~1s protection delay if ACTIVE_UNKNOWN becomes common (acceptable conservative default; note it).

## Opus round 5 (2026-08-11): NOT CONVERGED — 5 decisions needed, opus gave recommendations

Both holes fixed in principle, no infinite loops remain. 5 ambiguities to decide (opus recommendations):

### Decision D1 (BarrierFailed #1-2 behavior): REVISED — transition to IDLE (opus round 6)
Round 6 found "STAY in current state" is a BUG: it leaves the guard in a non-functional REMOTE_ACTIVE (state shows protecting, but barrier failed + lease suppressed/expires after 8s) -> phone steals while user thinks it's protected.
REVISED D1: On BarrierFailed: increment consecutiveBarrierFailures. If count >= threshold (3 transient, or 1 if permission_missing) -> terminal OFF per D2. ELSE (non-terminal) -> transition to IDLE, actions [DisableBarrier, ClearNativeLease]. IDLE's natural 5s idlePoll rate-limits retries; next AudioSourceObserved(REMOTE) from IDLE -> REMOTE_ACTIVE -> retry EnableBarrier. Bounded: max 3 attempts x 5s = 15s then terminal. Accurate state (IDLE = barrier-disabled), no misleading REMOTE_ACTIVE, no expired-lease window.

### Decision D2 (terminal state): OFF + GuardHealth=DEGRADED(BARRIER_FAILED)
Do NOT add a DEGRADED state to the state machine (keep OFF/PROBING/IDLE/LOCAL/REMOTE_ACTIVE/REMOTE_GRACE). On terminal (count>=3): transition to OFF, actions [DisableBarrier, ClearNativeLease], GuardHealth=DEGRADED(reason=BARRIER_FAILED, barrierActive=false). enabled flag stays true (OFF+enabled=true is coherent: enabled means "user wants guard on", state=OFF means "currently not protecting"). On next AacpConnected from this state: reset count, clear degradation, try again (automatic recovery if the condition was transient).

### Decision D3 (counter reset policy): reset only on successful EnableBarrier
consecutiveBarrierFailures resets to 0 only when an EnableBarrier completes with status.active==true. It PERSISTS across REMOTE_ACTIVE->GRACE->IDLE->PROBING->REMOTE_ACTIVE within one connection episode (same environment). It does NOT reset on AacpDisconnected (keep accumulating across connections) — so a permanently-broken environment reaches terminal and stops burning battery. The automatic-recovery path is: terminal state + next AacpConnected resets it (see D2).

### Decision D4 (permission_missing immediate terminal): YES, inspect status.state
On BarrierFailed, read PhoneSoundRoutingController.status.state. If == "permission_missing" (permanent): go terminal immediately (count 1 is enough). Else (registration_failed etc, transient): count toward 3.

### Decision D5 (BarrierFailed during REMOTE_GRACE): counts toward limit
BarrierFailed from any barrier-enabled state (REMOTE_ACTIVE, REMOTE_GRACE, PROBING) increments count and can trigger terminal. Prevents "phantom barrier" (system unregistered the policy but app thinks it's up). Terminal from REMOTE_GRACE: OFF + [DisableBarrier (idempotent), ClearNativeLease] + DEGRADED.

### Grace extension (Hole 2): SIMPLIFY — drop counters, keep 5000ms absolute budget
Remove graceExtensionCount and maxGraceExtensions. On REMOTE_GRACE entry: graceStartTime=now, phaseEnd=now+2500, deadline=now+500. On Tick in REMOTE_GRACE when lastObservedOwner in {NONE,UNKNOWN}: graceBudgetRemaining = (graceStartTime+5000) - now; if >0 -> phaseEnd=min(now+1000, graceStartTime+5000), deadline=phaseEnd, actions+=[RefreshNativeLease, RequestAudioSource]; else -> IDLE + [DisableBarrier, ClearNativeLease], graceStartTime=null. Note: keep RefreshNativeLease in the extension branch (opus round-3 had it; round-5 example omitted it but it's needed to keep the A2DP block alive during the extended grace).

### VERDICT (round 4)
Do NOT ship batch 1 until HOLE 1 + HOLE 2 are resolved. Everything else implementable.

## Opus round 7 VERDICT: CONVERGED (2026-08-11) — implementation checklist

Known accepted tradeoff (not blocking): 1 action-batch window (<100ms) where EnableBarrier failed, state=REMOTE_ACTIVE but barrier down — fail-safe (audio stays on phone speaker), v1 acceptable. Zero-window fix (TryEnableBarrier two-phase commit) deferred as optimization.

### Final file change list

**1. RemoteDeviceAudioGuard.kt (core state machine)**
- Fields: `consecutiveBarrierFailures: Int` (persists across states; resets only on AacpConnected-from-OFF recovery and on successful EnableBarrier), `localObservationCount: Int` (two-strike LOCAL counter, reset on entering/leaving REMOTE_ACTIVE), `graceStartTime: Long?`.
- PROBING: AudioSourceObserved(NONE) -> stay PROBING, deadline=min(current, now+probeRetry), [RequestAudioSource]. Tick at deadline -> IDLE fail-open.
- IDLE->REMOTE_ACTIVE: AudioSourceObserved(REMOTE, MEDIA/ACTIVE_UNKNOWN) -> REMOTE_ACTIVE, [EnableBarrier, RefreshNativeLease, RequestAudioSource].
- NEW BarrierFailed event handler: increment consecutiveBarrierFailures; count>=threshold (transient=3, permission_missing=1, via status.reason) -> OFF, [DisableBarrier, ClearNativeLease], GuardHealth=DEGRADED(BARRIER_FAILED, barrierActive=false), enabled stays true; else -> IDLE, [DisableBarrier, ClearNativeLease].
- REMOTE_ACTIVE two-strike LOCAL: AudioSourceObserved(LOCAL+phone-MAC+MEDIA/ACTIVE_UNKNOWN) -> increment localObservationCount; >=2 -> LOCAL [DisableBarrier, ClearNativeLease], reset; else stay REMOTE_ACTIVE. AudioSourceObserved(LOCAL+phone-MAC+CALL) -> immediate LOCAL (bypass debounce).
- REMOTE_ACTIVE->REMOTE_GRACE: graceStartTime=now, phaseEnd=now+2500, deadline=now+500, [RefreshNativeLease, RequestAudioSource]. Do NOT reset consecutiveBarrierFailures.
- REMOTE_GRACE extension: Tick + lastObservedOwner in {NONE,UNKNOWN} -> budget=(graceStartTime+5000)-now; >0 -> phaseEnd=min(now+1000, graceStartTime+5000), deadline=phaseEnd, [RefreshNativeLease, RequestAudioSource]; else -> IDLE, [DisableBarrier, ClearNativeLease], graceStartTime=null.
- AacpConnected from OFF: reset consecutiveBarrierFailures=0, clear GuardHealth degradation, -> PROBING.
- EnableBarrier success (status.active==true): reset consecutiveBarrierFailures=0.

**2. AirPodsService.kt (integration)**
- After action batch containing EnableBarrier, check PhoneSoundRoutingController status.active; if false -> dispatch RemoteDeviceAudioGuard.Event.BarrierFailed(status).
- setGuardEnabled(true) idempotent: registeredPolicy!=null -> re-apply preferred route + status.preferredRouteApplied=true; if status.state in {unregistration_failed, registration_cleanup_failed} -> force cleanup (unregister+null+re-register).

**3. PhoneSoundRoutingController.kt**
- getBarrierStatus() fills reason: state==registration_failed -> distinguish permission_missing vs transient from the captured exception.
- Ensure barrierActive field = status.active.

**4. RemoteDeviceAudioGuardTest.kt** — new tests: PROBING NONE stays; PROBING timeout->IDLE; two-strike LOCAL; CALL immediate; BarrierFailed transient 3->OFF; BarrierFailed permission_missing 1->OFF; OFF recovery (AacpConnected resets); grace extension budget 5s cap. Modify existing to assert new fields.

**5. Config constants (companion):** LOCAL_DEBOUNCE_COUNT=2, MAX_GRACE_EXTENSION_MS=5000, PROBE_TIMEOUT_MS=1500, TRANSIENT_BARRIER_FAILURE_THRESHOLD=3, PERMISSION_MISSING_BARRIER_FAILURE_THRESHOLD=1.

Batch 2 (CONFIRMING states + user override + localMac-invalid + battery fallback) deferred until batch 1 stabilizes on device.

## Test impact

- RemoteDeviceAudioGuardTest (~720 lines): batch 1 adds ~10 tests; batch 2 updates ~30 assertions + adds confirmation timeout/flip/commit tests.
- GuardHealthTest: barrierEnabled set unchanged (CONFIRMING states added to it).
- PhoneSoundRoutingController has no JVM test (reflective Android); verify on device.
