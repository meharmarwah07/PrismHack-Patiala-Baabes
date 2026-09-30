# Calo — verify TeachCheckpoint resumes teaching after a process kill

You're helping Mehar verify a specific, previously-unverified fix on `integration-test` (Calo, `com.calo`). Deadline 30 Sep, feature freeze 29 Sep. Fresh session, no memory of anything before this — read this whole prompt first.

## Background
`KNOWN_LIMITATIONS.md` (25 Sep entry) documented a serious bug found on a live test device: `CaloAccessibilityService` could be silently process-killed mid-teach (confirmed via `dumpsys accessibility` showing a crashed/replaced service instance), losing the entire in-progress teaching session with no user-visible error — the UI just stops updating, looking normal while actually broken.

That's since been fixed in code — `app/src/main/java/com/calo/teach/TeachCheckpoint.kt` now exists and is wired into `CaloAccessibilityService`:
- Steps are checkpointed to disk (atomic write) after every accessibility event during teaching.
- `onServiceConnected()` auto-loads any in-progress checkpoint and resumes teaching in the new process instance — touch capture re-armed, steps restored, target package restored.
- `MainActivity.onResume()` reconciles the UI against the service's actual state, so button text/status correctly reflect "still teaching" even after the process was recreated.
- Resume window: 30 minutes (`RESUME_TEACHING_MAX_AGE_MS`).

**Nobody has verified this against a real process kill on a real device.** That's your job tonight. If it works, `KNOWN_LIMITATIONS.md`'s stale "not fixed" entry needs correcting. If it doesn't work as designed, this becomes the top-priority bug before freeze — silently losing a demo teach mid-session is worse than almost anything else on the punch list.

## Environment notes (carried over from tonight's earlier testing, don't rediscover)
- Device: Realme CPH2381, ColorOS. `adb shell pm clear com.calo` fails (OEM restriction) — irrelevant here, not needed for this test.
- `adb` may not be on PATH: `$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"`, or add the folder to PATH for the session.
- Relevant logcat tags: `Calo`, `CaloTouchCapture`, `NLUClient`, `CaloTapTiming`, `CaloCredentialGatePerf`. Use:
  ```powershell
  adb logcat -c
  adb logcat -v time Calo:D CaloTouchCapture:D *:S
  ```
- Use `adb shell am kill com.calo` for this test, NOT `am force-stop`. `kill` simulates a low-memory process reclaim (the actual failure mode this bug is about) without also revoking the accessibility service grant — `force-stop` does revoke it, which would test a different (already-known, unrelated) failure mode and give a false negative.
- Build and install the current `integration-test` branch fresh before testing (`.\gradlew.bat installDebug`), so you're testing the actual checkpoint code, not a stale APK.

## Test sequence
1. **Start teaching a real flow** (any target app, e.g. Zomato) — do 2-3 real, deliberate taps, confirming each on-screen before the next, same discipline as every other teach session tonight.
2. **Confirm the checkpoint file is actually being written** before killing anything — this is a real assertion, not an assumption:
   ```powershell
   adb shell run-as com.calo cat files/teach_checkpoint.json
   ```
   Should show non-empty JSON with the steps you just taught. If this is empty or missing, stop — the checkpoint isn't being written and the whole test is moot; report that finding instead of proceeding.
3. **Kill the process**: `adb shell am kill com.calo`. Watch the logcat window — do NOT do anything else in the app for a few seconds, let the system reconnect the service naturally (this is what actually happens on a real OEM kill, not a manual relaunch).
4. **Check for the resume log line** — `CaloAccessibilityService.resumeInterruptedTeaching()` logs a warning on successful resume: `"Resuming a teaching session interrupted by a process restart: N step(s) recovered, targetPackage=..."`. Confirm it appears, and that N matches what you actually taught.
5. **Check the UI**, not just the log — open/foreground the Calo app. Does the button correctly say "Finish Teaching" (not "Start Teaching")? Does the status text reflect teaching-active? This matters as much as the log line — a correct internal resume with a UI that still looks wrong is still a real bug from the user's perspective.
6. **Continue teaching** — do 1-2 more real taps on the target app, confirming the resumed session actually keeps recording (not just holding onto the old steps statically).
7. **Finish teaching normally**, dump/review the saved flow. Confirm the full step count (pre-kill steps + post-kill steps) is present, in order, nothing dropped or duplicated at the resume boundary.
8. **Bonus, if time allows**: repeat once letting the OEM's own background-app killer do the kill naturally (put the app in the background, wait) instead of the manual `adb shell am kill`, to confirm this isn't just an artifact of how the manual kill was performed.

## What a failure looks like (don't rationalize it as success)
- No resume log line at all, or N=0 steps recovered.
- Resume log line present but the actual step count in the final saved flow doesn't match what was really tapped (steps lost or duplicated at the boundary).
- UI shows "Start Teaching" after the kill even though the service resumed internally — a real bug even if the underlying data survived, since the user would think nothing is happening and might tap "Start Teaching" again, stepping on the resumed session.
- Checkpoint file empty/missing at step 2 (nothing to resume from in the first place).

## Report format
1. Checkpoint file confirmed non-empty before kill: yes/no, what it contained.
2. Resume log line: exact text, step count recovered vs. actually taught.
3. UI state after resume: correct or not, be explicit.
4. Post-kill continued teaching: worked cleanly or not.
5. Final flow dump: step count and order correct end-to-end, or not.
6. Bonus natural-kill repeat, if attempted: same result or different.
7. Recommended `KNOWN_LIMITATIONS.md` update — either confirm the 25 Sep entry can be marked resolved (with today's date and this evidence), or, if it failed, what specifically broke so it can be prioritized correctly.
