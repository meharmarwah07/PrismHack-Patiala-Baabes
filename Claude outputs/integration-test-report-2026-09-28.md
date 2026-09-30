# Integration-test on-device verification — report

**Branch:** `integration-test` — started at `633a242`, ended this report at `e1445ce` (three commits landed mid-session; two from a parallel effort reaching the same findings this session made, see §3a/§5)
**Device:** Realme CPH2381, ColorOS, debug build
**Date:** 2026-09-28
**App target for taught flows:** `com.application.zomato` ("Order Pizza" / Domino's, margherita)

Summary up front: **T-safety passed cleanly. Two real bugs found this session (duplicate-click dedup gap, and a replay hang with no timeout) are now both fixed and verified — one fixed directly in this session, the other fixed in a parallel session while this one was live-testing the same issue.** One structural gap (`SUBMIT_SEARCH` not replayable) remains confirmed but undocumented — needs a `KNOWN_LIMITATIONS.md` entry before freeze. T3's "Thinking… cue" question was not confirmed either way by any session tonight — still open.

---

## 1. T-safety — PASS

`CredentialGate.check()` only runs from two call sites (a raw touch-down while teaching, and before every replay step) — it is not a passive screen-watcher, so simply standing on a sensitive screen does nothing. Once exercised properly (a tap during teaching, and a replay step), the gate fired distinctly and unambiguously twice, on two different apps:

- Zomato payment-adjacent screen: `gateClear=false`, `anchor=null` — tap blocked, not captured.
- Fampay (fintech): `gateClear=false`, `anchor=null` — tap blocked, not captured.

No crash, no ANR, no silent pass-through. Gate cost scaled with screen complexity as already documented in `CredentialGate.kt`'s own comments (~300ms on a 158-node Zomato screen vs. ~2-18ms on smaller screens) — a known characteristic, not a new regression.

**Logging gap found:** the teach-time gate verdict only appears in the `CaloTapTiming` debug tag (buried in a perf-timing line, `gateClear=...`), not as a distinct "blocked" message. There's no dedicated log line differentiating "gate blocked this tap" from any other reason a tap got dropped during teaching. Only the replay-time halt path logs clearly (`Calo: Replay result: Stopped for your safety at step N: ...`). Worth a follow-up: add an explicit log line when `credentialGateClear` is false in `CaloAccessibilityService.kt` teach path.

---

## 2. T1 (teach) — systematic capture issues found

Taught the same Zomato pizza-order flow **five times** across the session (re-teaching to isolate issues). Two problems reproduced **every single time**, regardless of how carefully/slowly taps were made:

### 2a. Duplicate `OPEN_SEARCH` steps — ROOT-CAUSED AND FIXED (see §5)
Every attempt captured the search-bar-opening tap as 2-3 separate, consecutive, identical-looking `CLICK` steps (escalating 2 → 2 → 3 across three of the attempts). Confirmed via direct observation this was **not** the user re-tapping carelessly — Mehar reported the search bar visibly does not open on the first physical tap, requiring a second. `TeachRecorder` correctly records what happens (real taps, since the first didn't register as an app-level click) — the actual bug was downstream, in the replay-time dedup that's supposed to collapse exactly this pattern. Root cause and fix in §5.

### 2b. `SUBMIT_SEARCH` — empty anchor, structural, not fixable by re-teaching
Zomato's search field only supports keyboard-enter submission (confirmed with Mehar — no separate tappable search icon exists in this app's UI). `TeachRecorder` correctly detects "no tap was captured while the keyboard was up" and records a `SUBMIT_SEARCH`-role step, but its anchor is **completely empty** (`resourceId=null, text=null, contentDescription=null, className=null, indexInParent=null`). Confirmed via code read: `ReplayEngine.kt` and `ReplayPlanner.kt` never reference `step.role` at all — the semantic role is captured for display purposes only (visible in the debug flow dump) and is dead weight in the actual replay path (consistent with the rest of the semantic-replay subsystem — `RoleMatcher`/`ContextPicker`/`FuzzyLabel` — being unwired, as already known coming into tonight).

**Practical consequence:** any taught flow that requires typing + submitting a search via keyboard cannot currently replay past that point. This needs `KNOWN_LIMITATIONS.md` entry before freeze. Closing it for real means either (a) teaching users to always tap a visible submit control when the app has one, or (b) adding real IME-submit replay support (a new capability — global action / synthesized `KEYCODE_ENTER` — not attempted tonight given the time and the need for its own design/testing).

### 2c. Weak/generic anchors
Several steps anchor only on generic, likely-reused Android resource IDs with no disambiguating text (`recycler_view`, `container` — both extremely common, non-unique IDs across Android apps generally). One step (an accidental `SCROLL` at the very start of one attempt) had **no** identifying anchor data at all except `className=android.view.View, indexInParent=0`, and on replay it resolved to a real-but-wrong node (`action SCROLL did not apply to resolved node`) rather than failing to resolve at all — i.e. a weak anchor can silently resolve to the *wrong* element, not just fail loudly. Not blocking, but worth keeping in mind when reviewing any taught flow's dump before trusting it.

**Debug-tooling gap found:** the `FLOWDUMP` debug print (`CaloOrchestrator.kt` ~line 476) only prints 5 of `ElementAnchor`'s 7 fields — it omits `hintText` and `contextLabel`. This made two attempts' "identical-looking" duplicate steps *look* byte-identical in the dump when they likely weren't (see §5) — reviewing a flow dump alone is not sufficient to judge true anchor duplication. Worth adding the missing two fields to that print statement.

---

## 3. T2 (exact replay) — gate and safety checks pass; completion blocked by §2b

- **CredentialGate per step:** confirmed correct every run — one gate check logged immediately before every step attempted, including steps that were later skipped as duplicates.
- **No crash, no ANR:** confirmed across every replay attempt tonight.
- **Never completed end-to-end**, for the confirmed structural reason in §2b (`SUBMIT_SEARCH`), not flakiness. Once that step was reached, replay produced a clean, fast `Stuck` result — safe failure, not a crash or wrong action.
- **Duplicate-step handling before the fix:** redundant `OPEN_SEARCH`/`recycler_view` steps were each individually re-attempted (`performAction` typically failed, falling back to a synthesized finger-tap that usually "succeeded" in the sense of completing, landing on an already-open element). Not observed to cause any dangerous mis-tap, but wasteful and non-deterministic (fallback tap coordinates varied between otherwise-identical replay attempts of the same flow).

### 3a. Replay could hang indefinitely with zero recovery — FOUND TONIGHT, NOW FIXED
Confirmed **twice** during this session, under two different circumstances:

1. Once when the target app's task got killed mid-replay (`ActivityManager: Killing ... com.application.zomato ... remove task`) — replay simply stopped producing any log output forever afterward, no crash, no ANR, no `Stuck`/`Halted`/`Completed` result.
2. Once with the target app alive and fully responsive on-screen (confirmed directly by Mehar interacting with it) — `Calo`'s off-main-thread replay coroutine was silently stuck internally, invisible to the user, between two steps that both succeed instantly in other runs.

In both cases: process alive, no ANR in `dumpsys dropbox` for the day, no crash log, foreground app fine — the hang was entirely internal to `ReplayEngine`'s coroutine, with no timeout anywhere in the replay loop. Recovery required `am force-stop` (which also silently drops the accessibility service at the OS level — see §6).

**Fixed later the same night** (commit `3779c8e`, refined in `e1445ce`, both landed on `integration-test` while this session was still running): every `NodeProvider` call in `ReplayPlanner.replay()` is now run on a worker thread and bounded by a 5000ms timeout (`STEP_TIMEOUT_MS`), chosen against tonight's real latency data (>10x `CredentialGate`'s worst observed cost of ~350ms). A timed-out call now surfaces as `ReplayResult.Stuck` instead of hanging forever.

**One sharp edge the fix itself documents honestly rather than hides:** `future.cancel(true)` against a blocked Binder IPC call is best-effort — a blocked native call isn't guaranteed to honor `Thread.interrupt()`, so a timed-out *action* (`performClick`/`performSetText`/`performScroll`, as opposed to a timed-out read) can still land on the device seconds later, unsupervised, after Calo has already reported `Stuck`. Mitigated via a new `ReplayResult.Stuck.actionMayHaveExecuted` flag: `CaloOrchestrator.handleReplayResult()` now skips its normal retry-or-stop question when this is true (never stacking a second action on top of a possibly-already-executed one) and tells the user out loud that the last action may not have landed cleanly. Documented in `KNOWN_LIMITATIONS.md` (28 Sep) as **mitigated, not fixed** — there is no reliable way to cancel a blocked native IPC call from the JVM side, so "a taught flow occasionally performs one extra unrequested tap/type/scroll after reporting Stuck" is an accepted residual risk on a slow enough device/screen, not eliminated.

**Verification done tonight:** `:domain:test` and `:app:compileDebugKotlin` both pass against the combined state (dedup fix + timeout fix); rebuilt and reinstalled on-device. **Gap:** I did not find or write a dedicated unit test exercising the timeout-triggers-Stuck path itself (a slow/blocking fake `NodeProvider`) — the existing suite passing confirms no regression, not that the new behavior is covered. Also did not re-attempt reproducing an on-device hang against this new build tonight (both original hangs were awkward to trigger on demand) — recommend a deliberate repro attempt before fully trusting this closes the issue for judging day.

### 3b. Possible concurrency gap — flagged, not fully confirmed
`CaloAccessibilityService.mode` (a plain `var`, not `@Volatile`, not an atomic) is checked against `Mode.TEACHING` in the touch-capture path but is **never checked against `Mode.REPLAYING`** anywhere before starting a new replay. Observed once: two voice-triggered replay attempts fired back-to-back (confirmed by Mehar — he spoke the trigger twice in quick succession after seeing the first get stuck), and the log showed a second `Replay starting: steps=6` begin before the first (`steps=7`) had logged any result. The `steps=6` vs `steps=7` discrepancy itself is unexplained — there was only one saved flow (7 steps) at the time. Worth a closer look post-freeze: at minimum, add a reentrancy guard so a second replay/utterance trigger while one is in-flight is rejected or queued rather than silently allowed to run concurrently against the live screen (this would also compromise the "gate is checked immediately before every action, no gap" invariant `ReplayPlanner`'s own class doc relies on).

---

## 4. T3 (paraphrase/voice replay)

- **NLU matching:** worked correctly every time it was exercised — confidence 0.9-0.95, correct flow matched, correct trigger text logged (`NLU match: matchedFlowId=... confidence=... candidates=[...]`).
- **NLUClient latency/timing:** **could not be measured** — `NLUClient.kt` has no success-path logging at all (`Log.e` only, on missing API key / HTTP failure / exception / parse failure). The original test brief's instruction to "watch NLUClient timing lines" for latency doesn't correspond to anything the code currently emits on a successful call. Not a bug, but the brief's expectation was wrong; if latency needs to be measurable going forward, a `Log.d` with duration around the Groq call in `NLUClient.kt` would need to be added.
- **"Thinking…" cue holding through the wait:** **not confirmed either way tonight.** This was the one specific thing the original brief flagged as "a known gap being fixed tonight — confirm it actually covers a multi-second wait now" — session time got consumed diagnosing the replay-hang and dedup issues instead. Needs a dedicated check before judging: trigger T3, and have someone explicitly listen for whether the spoken/shown "Thinking…" persists through the full NLU round-trip or cuts out early.
- **Stuck-flow question/answer feature: confirmed working as designed** (not originally one of the T1-T3 checklist items, but observed live). When replay went `Stuck` at the `SUBMIT_SEARCH` wall, `CaloOrchestrator` correctly built a spoken question, spoke it via TTS, and opened the mic to listen for a voice answer ("stop" vs. retry-with-new-value) — exactly matching the documented `handleReplayResult` behavior. Not deeply tested (the actual stop/retry response wasn't exercised), but the mechanism fires correctly.

---

## 5. Bug found, fixed, and verified: `isDuplicateClick` dedup gap

**Root cause:** `ReplayPlanner.isDuplicateClick()` (`domain/src/main/kotlin/com/calo/domain/replay/ReplayPlanner.kt`) compared full `ElementAnchor` equality (`previous.target == step.target`). `ElementAnchor` is a Kotlin data class whose auto-generated `equals()` includes **all 7 fields**, including `contextLabel` — text captured live from the element's surrounding on-screen context at the exact moment of each tap (`TeachRecorder.kt:470`, via `NodeWalker.contextLabelFor`). `contextLabel`'s own doc says it exists purely for disambiguating between multiple candidate matches (`ContextPicker`), and `hintText`'s doc explicitly says "not used to re-find the element" — neither is meant to be part of element *identity*.

Two taps landing on the visually-identical search bar, captured ~200-300ms apart during its opening animation, plausibly differ in `contextLabel` (surrounding text shifting slightly as the UI transitions) even though every field actually used for re-finding the element (`resourceId`, `text`, `contentDescription`, `className`, `indexInParent`) is identical. That silently defeated the dedup check for exactly the capture-artifact scenario it was built for (the class doc even cites the original motivating bug: "a Zomato flow recorded the same 'Domino's Pizza' CLICK anchor 5 times in a row").

**Fix applied (this session):** `isDuplicateClick` now compares only the 5 identity fields `NodeWalker`'s resolver actually searches by, ignoring `hintText`/`contextLabel`.

**Verification (this session):**
- `:domain:test` — passes clean (includes existing `ReplayPlannerTest` coverage; the change is strictly more permissive than before, so nothing that was previously considered a duplicate stops being one).
- `:app:compileDebugKotlin` — passes clean.
- **On-device, live confirmation:** re-taught the flow, captured the same duplicate `OPEN_SEARCH` pattern again, replayed it, and the second (duplicate) step was silently skipped — no re-attempt, no fallback tap, no log line for it at all. Before the fix, every run showed the duplicate individually attempted and falling back to a synthesized tap.

**Note:** this exact fix landed as a proper commit (`99d4eaa`) during this same session — a parallel session/effort reached the identical diagnosis and fix independently (same root cause, same 5 fields, near-identical reasoning) and committed it to `integration-test` while this session was still live-testing it from an uncommitted working-tree edit. A later commit (`e1445ce`) documents it as future-proofed against lane-a-teach's `ElementAnchor` fields once merged — though that commit's message claim that this branch "doesn't have those fields yet" doesn't match what this session directly observed (`contextLabel`/`hintText` were populated and visible in flow dumps and Stuck-error messages throughout tonight's testing on `integration-test`); minor commit-message inaccuracy, not a functional concern.

This fix is a small, isolated, unit-tested, on-device-verified change to one private function. Confirmed present and working as of `e1445ce` (current `integration-test` HEAD).

---

## 6. Environment / process notes worth keeping for next time

- **The original session brief's `DEBUG_RESET` command was wrong** — it used `com.calo.DEBUG_RESET`, but the manifest and `DebugTriggerReceiver` both declare `com.calo.debug.DEBUG_RESET` (with the `.debug.` segment). The wrong action silently no-ops (no error, no receiver match) — always use `adb shell am broadcast -a com.calo.debug.DEBUG_RESET -p com.calo`.
- **The suggested logcat tag filter was incomplete.** `Calo`, `CaloTouchCapture`, `NLUClient` alone miss `CaloTapTiming` (teach-time gate verdict + full tap-resolution timing) and `CaloCredentialGatePerf` (gate tree-walk cost). Use all five: `Calo:D CaloTouchCapture:D NLUClient:D CaloTapTiming:D CaloCredentialGatePerf:D *:S`.
- **`am force-stop com.calo` disables the accessibility service at the OS level**, not just the process — `enabled_accessibility_services` reads back `null` afterward. Expected Android behavior, but it means recovery from a hang always needs a manual re-enable in Settings, not just relaunching the app. Needed twice tonight.
- **`pm clear com.calo` still fails with `SecurityException`** on this device (OEM restriction) — `DEBUG_RESET` remains the only working flow-wipe path here, as already known.
- Logcat capture died silently (`exit code 255`, file-lock error) several times tonight, each time coinciding with a USB transport reconnect (`transport_id` incrementing) — not a Calo issue, just this cable/port being flaky. Worth having a spare cable for judging day so a dead log stream doesn't eat time again.

---

## Priority punch list for the team before freeze (2026-09-29)

1. **Document known limitation** — `SUBMIT_SEARCH` (and any role-based replay behavior generally) is captured but not actually usable during replay; add to `KNOWN_LIMITATIONS.md` so this isn't a surprise during judging if a demo flow needs keyboard search-submit. Not yet documented there as of this report — everything else found tonight either got fixed or already has a `KNOWN_LIMITATIONS.md` entry from the parallel session; this one doesn't yet.
2. **Confirm the "Thinking…" cue** actually holds through a full multi-second NLU wait — this was flagged as fixed tonight but never actually confirmed either way, by either session.
3. **Consider a replay reentrancy guard** (§3b) — `CaloAccessibilityService.mode` still has no check against `Mode.REPLAYING` before starting a new replay/utterance handling. Same family of risk as the hang bug (unpredictable concurrent action dispatch against a live screen), not yet addressed as of this report.
4. **Do a deliberate repro attempt against the new timeout fix** (§3a) before trusting it closes the hang issue for judging — verified by tests/compile/install tonight, but the original hangs weren't easy to trigger on demand, so the fix's real-world effectiveness against the exact scenario isn't independently re-confirmed yet.
5. Already fixed and verified tonight, no action needed: duplicate-click dedup (§5, commits `99d4eaa`/`e1445ce`), replay hang/no-timeout (§3a, commit `3779c8e`, with `actionMayHaveExecuted` mitigation in `e1445ce`).
6. **See also `KNOWN_LIMITATIONS.md`'s own 28 Sep entries** (from a parallel verification session tonight, not covered by this report): a confirmed-fixed teach-session process-kill/resume issue, and a new, documented-not-fixed intermittent gap where raw touch capture can silently stop delivering `onMotionEvent` after a resume — compounding with the pre-existing Finding-6 SCROLL/CLICK misclassification risk. Worth reading alongside this report for the full picture of tonight's findings across both sessions.
