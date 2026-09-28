# Calo — known limitations (23 Sep 2026, updated 25 Sep 2026, updated 28 Sep 2026)

## 28 Sep 2026 — a timed-out action step is not guaranteed cancelled

`ReplayPlanner` now bounds every `NodeProvider` call with a 5000ms timeout
(see the fix committed this session for the underlying hang this closes).
For a read-only call (`currentScreenSignals`/`findNode`/`findNodeByValue`/
`awaitIdle`) that's the whole story: the call is abandoned, nothing on the
device changed, replay reports `Stuck` and stops safely.

For an *action* call — `performClick`/`performSetText`/`performScroll` —
it is not the whole story. The timeout gives up waiting on the Kotlin side
by calling `future.cancel(true)`, but that is best-effort against a
genuinely blocked Binder IPC call into the target app's process; a blocked
native call is not guaranteed to honor `Thread.interrupt()`. If the tap/
type/scroll was already in flight when we gave up on it, it can still land
for real on the device seconds later, after Calo has already told the user
"stuck" and (in the worst case) after the user has already said "stop."

Mitigated, not fixed, this session: `ReplayResult.Stuck` now carries
`actionMayHaveExecuted`, set true only for a timed-out action call.
`CaloOrchestrator.handleReplayResult()` checks this before asking its
usual "should I pick something else, or stop?" question — when true, it
skips that question entirely (never offers retry, since a second action on
top of a possibly-already-executed one is exactly the double-fire risk
being avoided) and goes straight to a stop, telling the user out loud that
the last action may not have completed cleanly. This narrows the blast
radius (no automatic retry stacks a second action on the uncertain one,
and the user is told the truth instead of a falsely-confident status) but
does not and cannot guarantee the original action didn't fire. There is no
clean fix available for this before freeze — you cannot reliably cancel a
blocked native IPC call from the JVM side. Treat "a taught flow occasionally
performs one extra, unrequested tap/type/scroll after reporting Stuck, on a
device/screen combination slow enough to hit the 5000ms timeout" as a real,
accepted residual risk, not a hypothetical.

## 25 Sep 2026 — live-device T1 test session findings

Ran a real T1 attempt (teach "order a Margherita pizza from Domino's on
Zomato") on a physical device (Oppo/OnePlus-family, ColorOS, Android,
`com.calo` build installed 25 Sep 17:41) against the real Zomato and
Domino's apps. Two things found this pass, one fixed, one not.

### Fixed: teaching that starts from the home screen recorded the wrong target app

Confirmed by directly reading the on-device Room database
(`flow_library.db`, pulled via `run-as`) before touching any code: two
pre-existing saved flows both had `targetPackage = "com.android.launcher"`.
Both were taught by tapping the target app's home-screen icon as the first
action — exactly how a judge doing "perform the taps once" would start.
`TeachRecorder.onAccessibilityEvent()` was latching `targetPackage` from
whichever package's event arrived first, with no exclusion for the
launcher, so every real teach session that opens the target app from the
home screen (there's no other way to leave Calo and reach it) recorded
`com.android.launcher` as the flow's target — permanently breaking replay
with "Couldn't bring com.android.launcher to the foreground."

Fixed (found already fixed in the working tree mid-session, likely a
concurrent edit): `CaloAccessibilityService.startTeaching()` now resolves
the device's actual launcher package at runtime
(`PackageManager.resolveActivity(ACTION_MAIN + CATEGORY_HOME)`, not a
hardcoded string) and passes it to `TeachRecorder` as
`launcherPackageName`, which now excludes launcher-package events the same
way it already excluded Calo's own package. Rebuilt (`:app:assembleDebug`,
real toolchain) and reinstalled; confirmed via `dumpsys package` the
installed build's timestamp matches this fix.

### Not fixed — teaching does not survive a normal-length session on this device

Five consecutive T1 attempts this pass — two fully scripted, one with a
real human doing the taps in real time — all failed to produce a saved
flow. `CaloOrchestrator.finishTeaching()` silently no-ops
(`onSaved(false)`, no user-visible error beyond a status string) if
`CaloAccessibilityService.instance` is a different instance than the one
`startTeaching()` was called on. Confirmed directly: `dumpsys accessibility`
showed `Crashed services:{{com.calo/...CaloAccessibilityService}}` mid-session,
and `adb shell logcat --pid=<pid>` showed a `--------- beginning of main`
cold-start line for a **new** process, timestamped seconds after the user
said they'd reached the payment screen — the process backing the in-progress
`TeachRecorder` had been killed and silently replaced by a fresh one with
`teachRecorder == null` before the finish signal arrived. No Java exception
or stack trace exists anywhere in logcat or `dumpsys dropbox` for this —
it's a plain process kill, not a crash in Calo's own code.

Ruled out as the cause: standard Doze/battery-optimization whitelist
(`dumpsys deviceidle whitelist` already listed `com.calo`), and Settings →
Apps → Calo → Battery's "Allow background activity" (already on). One real
misconfiguration was found and fixed — "Pause app activity if unused" was
ON for Calo in App Info — but the kill recurred even after turning it off
and confirming a clean, non-crashed service, so that wasn't the sole cause
either.

**This is not confirmed as ColorOS-specific.** `CaloAccessibilityService`
runs with no foreground-service protection and no persistence of
in-progress teaching state — `TeachRecorder`'s steps live only in memory
on the service instance. Any Android device reclaiming memory from a
bound-but-not-foreground accessibility service mid-session — standard
behavior, not exotic OEM aggression — would lose the entire teaching
session silently, with nothing in the UI indicating anything went wrong
(the marble/status text just never updates because the callback that
would update it never fires). A live judged demo that takes more than
a minute or two to walk through a real multi-screen app (exactly what
T1/T8 ask for) is therefore at real risk of silently losing the whole
teach, through no fault of the person teaching it.

**Practical implication:** T1 (and everything downstream that depends on
having a saved flow — T2 through T14) could not be verified end-to-end
this pass. Also worth separately noting: the account's saved addresses on
this test device are both labeled "Home" (one deliverable, one not) —
there is no "Work" address, so T6 (slot: address, "deliver to work") can't
be exercised as written without first adding one.

Fix direction (not implemented this pass): run teaching with a foreground
service (visible notification) for the duration of the session, and/or
checkpoint `TeachRecorder`'s steps to disk incrementally rather than
holding them only in memory, so a process death loses at most the last
step instead of the whole session.


This is the flat, unhedged list: what's genuinely unverified, what's a real
bug found by reading the code (not just "TODO"s left in comments), and what
README's own "written but unverified" section got overtaken by since it was
written. Same rule as README: every claim here says how it was checked.
Where I found something by direct code inspection this pass, I say so and
cite the file — none of this is inferred from what the code is *supposed*
to do.

## Resolved since README's original "written but UNVERIFIED" list

- **Room codegen.** README's numbered list still says `kapt` generating
  `FlowDao_Impl` is untested — that's stale. README's own later
  "Slot-promotion UI (23 Sep 2026)" section records that `:app:assembleDebug`
  built successfully against the real Android Gradle Plugin and real Room
  annotation processor (Temurin 17 + a generated Gradle wrapper now exist in
  this repo), not the hand-written stubs the rest of the file describes.
  That's a real compiled, packaged debug APK. It has not been *installed or
  launched*, so this only proves the generated DAO compiles and links, not
  that a query round-trips correctly at runtime.
- **Groq model name.** `NLUClient.DEFAULT_MODEL` is `openai/gpt-oss-20b`,
  not the `llama-3.1-8b-instant` the original README text cites (that model
  is gone from Groq's lineup). The constant in the current file already
  reflects the newer model — re-check `console.groq.com/docs/models` again
  before a live demo regardless, Groq's catalog moves fast.

## Still open, exactly as README originally flagged them

- **Live-mic path.** `VoiceInputManager`'s own doc comment still says,
  unchanged: "not exercised on a device/emulator with a microphone in this
  build pass." Nothing in the current code contradicts that. Treat the
  voice-input path as unverified until it's actually run against a real
  mic — don't trust a prior claim that it was (see "Claims that don't match
  the repo" below).
- **`ACTION_SET_TEXT` failure path.** `ReplayPlanner`'s handling of
  `performAction()` returning `false` (Stuck, not silently success) is real
  code and is unit-tested for the *sequencing* — but nothing in this repo
  demonstrates it firing against a real custom widget that actually rejects
  `ACTION_SET_TEXT`. Only the happy path (a normal text field accepting
  the action) has any device evidence behind it, and that evidence is one
  of the claims below that doesn't hold up to scrutiny either.
- **Credential gate on a real screen.** README's numbered unverified list
  (item 1) still lists this as open. A later commit message
  (`86db2f4`, "Cross-app voice replay with launch-and-wait; credential gate
  verified halting on real password screen") claims otherwise — see below,
  this is exactly the kind of claim this file exists to not just repeat.

## Multi-flow disambiguation — confirmed still absent

`NluResponseParser` produces a `confidence` value (0.0–1.0, defensively
clamped and defaulted), and `NluPrompt` asks the LLM for "your own
calibrated" estimate. `CaloOrchestrator.handleUtterance()` never reads
`match.confidence` anywhere — grep the file, it isn't referenced once
outside the parser and the prompt that produces it. A 0.05-confidence
guess is acted on exactly like a 0.95-confidence one, and there is no
disambiguation UI (no "did you mean X or Y" surface) anywhere in
`MainActivity`. If two taught flows are close enough in meaning to
confuse the LLM, Calo will silently run whichever one it picked, with no
signal to the user that it was a close call.

## `AccessibilityEvent.source == null` — confirmed still present, unhandled

`TeachRecorder.onAccessibilityEvent()`
(`app/.../teach/TeachRecorder.kt:27`) is `val source = event.source ?:
return` — a null source silently drops the entire event: no step
recorded, no log line, no signal to the teaching UI that anything was
missed. Nothing in the current code adds retry, logging, or a
null-source path. If this fires mid-teach (it has fired before, per
project history, on both a `FloatingActionButton` and an ordinary
`LinearLayout` row on at least one emulator/Android version), the taught
flow just ends up one step short with no indication why. Treat "the step
count after teaching looks short" as a real, silent failure mode to
watch for, not a hypothetical edge case.

## Slot-promotion UI — wired in, but narrower than it looks

The review dialog between "Finish Teaching" and the save dialog is real
(`MainActivity.showSlotReviewDialog()`), and it does call
`TeachRecorder.promoteToSlot()`. Two caveats, both confirmed by reading
`SlotResolver.kt` directly, not by assumption:

- **CLICK-step slots do nothing at replay time.** The review dialog lets a
  `CLICK` step (one whose target had visible text) get promoted, exactly as
  the task spec asked for. `SlotResolver.resolveValue()` returns `null` for
  every `ActionType` except `SET_TEXT`. The promoted slot is saved as a real
  `SlotDefinition`, round-trips through Room, and shows up promoted in the
  UI — and has zero effect on what actually gets tapped during replay. Only
  typed-text slots generalize a replay today. If a demo script needs
  "pick a different menu item by voice" (a CLICK-driven choice, not a typed
  one), that path does not work yet, full stop.
- **Never installed and tapped through on a device this pass.** The only
  verification behind the slot-review dialog is a successful
  `:app:compileDebugKotlin` / `:app:assembleDebug` (real toolchain, not
  stubs) and an unchanged `:domain:test` pass. Nobody has opened the app,
  taught a flow with a `SET_TEXT` step, tapped through the new dialog,
  promoted a slot, saved it, and confirmed voice replay actually
  substitutes a new value. That is a materially weaker claim than "this
  works" — see the next section for why that distinction matters
  specifically on this project.

## Claims that don't match the repo — check before repeating any of them

This project has a documented history (see this file's own git history and
prior session notes) of a session claiming a fix or a device verification
that then turned out not to actually be in the repository. Concretely,
checked directly against the current `TeachRecorder.kt` this pass:

- A prior claim that self-recording (the "start teaching" tap itself
  getting captured as flow step 1) was fixed by having
  `CaloAccessibilityService` ignore events from Calo's own package. **Not
  in the code.** Neither `CaloAccessibilityService.onAccessibilityEvent()`
  nor `TeachRecorder.onAccessibilityEvent()` filters on `event.packageName`
  anywhere, and `accessibility_service_config.xml` sets no
  `android:packageNames` restriction either. If this bug was ever fixed, the
  fix isn't present now.
- A prior claim that spurious `SCROLL` steps (incidental
  `TYPE_VIEW_SCROLLED` events from list animations, not real user scrolls)
  were eliminated by no longer auto-recording scroll steps during teaching
  at all. **Not in the code.** `TeachRecorder`'s `TYPE_VIEW_SCROLLED` branch
  unconditionally appends an `ActionType.SCROLL` step, same as `CLICK` and
  `SET_TEXT`.
- A prior claim that the `SET_TEXT` anchor bug (recording the field's
  *post-typed* value as `ElementAnchor.text`, which is useless for
  re-finding an empty field at replay time) was fixed by reading
  `event.beforeText` instead of the node's current text. **Not in the
  code** — there is no reference to `beforeText` anywhere in this
  repository (checked by search, not by reading one call site).
  `anchorFor()` still reads `node.text` directly, which for a
  `TYPE_VIEW_TEXT_CHANGED` event is the value *after* the keystroke. A
  `SET_TEXT` step on a field with no `resourceId` and no stable
  `contentDescription` (e.g. a bare "First name" field) will record an
  anchor keyed on text the field won't contain at replay time, and will
  have to fall through to the `className` + `indexInParent` last resort —
  which works only if nothing else on screen shares that class and
  position.

None of this means the app doesn't work — it means three specific,
previously-claimed-fixed bugs are, as of this pass, back in (or never left)
the exact state they were in when first found. Re-verify each on a real
device before trusting it, the same way `openai/gpt-oss-20b` and
`:app:assembleDebug` were re-verified above by actually running something
and reading the output, not by trusting a prior summary.

**Same standard applies to `86db2f4`'s credential-gate claim above:** the
commit's code changes (cross-app launch-and-wait, the NLU prompt wording
update, the debug receiver additions) are real and present in the repo.
Nothing in the repository — no test output, no log, no README section —
documents what screen was actually shown or what was actually tapped when
the credential gate was supposedly verified against a real password
screen. README's own numbered unverified list was never edited to remove
that item. Given the three claims immediately above, treat "credential
gate verified on a real screen" as **not yet independently confirmed**,
not as settled — this is the single highest-priority thing to re-test
before a demo, exactly as README's original item 1 already said.

## Cross-app launch — works by design, has a known gap

`launchAndWaitForForeground()` launches the target app's default entry
point (whatever tapping its home-screen icon opens), not the specific
screen a flow was taught from. If that screen isn't reachable from the
app's default open state — e.g. the flow was taught starting from deep in
a cart or search-results screen — replay will resolve zero anchors and
correctly report `Stuck` rather than tapping the wrong thing, but it won't
complete the flow either. This is stated directly in
`CaloOrchestrator`'s own doc comment, not something found by inspection —
included here because it's exactly the kind of gap that matters for a
multi-app judged demo (see `TARGET_APPS.md`).

## `awaitIdle()` is a fixed delay, not a real settle signal

`ReplayEngine.awaitIdle()` is `Thread.sleep(400)` between steps. Its own
comment already flags this as a simplification, not a window-content-changed
listener. A real screen transition slower than 400ms (a slow network
fetch on a menu screen, for instance) will make the next step's node
resolution fail and report `Stuck` on a flow that would otherwise have
completed. Not a correctness bug — replay never silently proceeds against
a stale screen — but it is a reliability gap worth budgeting demo retries
for.
