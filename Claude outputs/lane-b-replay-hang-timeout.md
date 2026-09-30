# Lane B — urgent: replay can hang indefinitely with zero recovery, zero log signal

You're on Lane B (`lane-b-replay`) on Calo (`com.calo`). Deadline 30 Sep, feature freeze 29 Sep — **this is the top-priority item before freeze.** Read this whole prompt first.

## What was found (confirmed on-device, 2026-09-28, `integration-test` branch)
Live replay testing found `ReplayEngine`'s step loop has **no timeout anywhere**. Confirmed hanging indefinitely twice, under different circumstances:

1. Target app's task got killed mid-replay (`ActivityManager: Killing ... remove task`) — replay produced zero further log output forever after. No crash, no ANR, no `Stuck`/`Halted`/`Completed` result. Process stayed alive; the coroutine was simply waiting on something that would never resolve.
2. Target app alive and fully responsive on-screen — the off-main-thread replay coroutine went silently stuck between two steps that both succeed instantly in every other run. No log signal distinguishing this from normal in-progress work.

In both cases the only recovery was `am force-stop com.calo` — which also drops the accessibility service at the OS level, requiring a manual re-enable in Settings.

**User-facing consequence:** Calo would appear to simply stop responding, indistinguishable from "still thinking." A demo hitting this live looks like the whole app froze, with no error message, no fallback, no way out except a support-diagnosed force-stop.

**One caveat on the evidence, for your own diagnosis:** logcat capture also died silently from USB flakiness several times during tonight's session, unrelated to this bug. It's possible (not confirmed) that hang case 2's "zero log output" partly reflects a dead capture pipe rather than a fully stuck coroutine — worth keeping in mind as you investigate, though it doesn't change the underlying fact that there is no timeout in the loop, which is a real gap regardless of exactly how each incident happened.

## Task
1. **Find where the step loop actually blocks.** Likely candidates: waiting on `rootInActiveWindow()` / node resolution against a window that no longer exists (case 1 — target task killed), or an internal wait/poll inside `pollFor`-style retry logic that has no bound (case 2). Don't guess — trace the actual suspension point.
2. **Add a timeout around each replay step** (or around the whole replay call, whichever is structurally right) that resolves to a `Stuck`/failure result on expiry — not a crash, not a silent hang. This should route through the existing `handleReplayResult`/Stuck-question path in `CaloOrchestrator.kt` so the user gets a spoken "something went wrong, want to retry or stop" rather than dead air.
3. **Pick a timeout value deliberately and justify it.** You have real latency data from tonight: normal steps complete near-instantly; `CredentialGate.check()` alone can take up to ~350ms on a complex screen. A few seconds per step is a reasonable starting point — don't pick something so aggressive it false-positives on a legitimately slow-but-fine screen transition, and don't pick something so loose it's still a multi-minute dead-air wait in practice.
4. Reproduce **case 1** yourself (kill the target app's task mid-replay via `adb shell am force-stop <target-pkg>` while replay is running) to verify the fix actually catches it — this is the more clearly-diagnosable of the two, and a good regression check.

## Rules
- Don't touch `CredentialGate` detection logic or `TeachRecorder` — this is scoped to the replay step loop's control flow, not safety semantics or teaching.
- Full JUnit suite must stay green.
- Keep the fix minimal and reviewable — this is landing the night before freeze, not the moment for a broader `ReplayEngine` refactor.
- If you find the root cause is something you can't safely fix in the time available (e.g. it requires touching `CredentialGate`'s own IPC calls), report that clearly rather than forcing a risky patch — a documented known-limitation is better than a rushed fix that introduces a new bug this close to freeze.

## Report format
1. Where the loop actually blocks (the real suspension point, not a guess).
2. Timeout value chosen and why, against tonight's latency data.
3. Confirms it resolves to `Stuck`/failure (not crash, not hang) on expiry, and that the user actually hears/sees something rather than dead air.
4. Case 1 (killed-task) reproduction: before/after.
5. JUnit count before/after, commit hash, confirm branch `lane-b-replay`.
