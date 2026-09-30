# Calo — known limitations (23 Sep 2026, updated 25 Sep 2026, updated 28 Sep 2026 x2, updated 30 Sep 2026)

## 30 Sep 2026 — Jetpack Compose surfaces are only partially reachable

Everything in this section was found on-device against the real Zomato app
on submission day, with `uiautomator dump` and replay logs as evidence.
Zomato's search-results screen is Compose-rendered, and Compose exposes a
much thinner accessibility surface than a View-based screen. Four distinct
consequences, all of which we now handle — none of which we consider
solved.

### Result cards accept ACTION_CLICK and ignore it
A Compose result card is an `android.view.View` with no resource id, marked
`clickable="true"`. `performAction(ACTION_CLICK)` on it returns **true**
while the app does nothing: Compose handles the tap in its own gesture
code, and Android's accessibility layer reports only that the action was
*dispatched*, never that it took effect. Replay then continued against a
screen that had not changed.
**What we do:** for a node of that shape (`resourceId == null`,
`className == android.view.View`) `ReplayEngine.performClick` dispatches a
real touch gesture *instead of* `ACTION_CLICK`, so exactly one input
reaches the app and there is no double-fire risk.

### Tapping the climbed ancestor's centre can miss the element
`NodeWalker` matches a label (a `TextView`) and climbs to its nearest
clickable ancestor. That ancestor can be larger than the card, so its
centre is not on the card: measured on-device, the gesture landed at
**(540,405)** while the card spans `[36,417][1044,711]` — twelve pixels
above it, in the gap. Four separate "make the tap more realistic" changes
failed for this reason before the coordinates were printed.
**What we do:** `ResolvedMatch` now carries `matchedBounds`, the originally
matched element's rectangle captured *before* the climb, and the gesture
targets that — the visible label, exactly where a person taps.

### Click events are dispatched on the window root
During teaching, Compose screens fire `TYPE_VIEW_CLICKED` with
`event.source` set to the fragment root (`…:id/root`), which spans the
whole window. Recording that verbatim produced anchors that match the
entire screen — that is, nothing — and replay blind-tapped the screen
centre (always 540,1206).
**What we do:** a source covering ≥90% of the window is treated as no
source at all and routed through the existing label-based recovery;
at replay, an anchor resolving to the whole window is refused, and a CLICK
step with no role, no slot and no readable label is skipped rather than
guessed at.

### Teach-time capture is not deterministic
`recordClickWithoutSource` needs a cached screen from the target package
captured within 2000ms to resolve a null-source click. When two taps are
far apart, or intervening scrolls are filtered as noise, the cache goes
stale and the second tap is **dropped, fail-closed**. Observed directly:
identical teaching gestures produced a 2-tap recording (which replays
correctly) and a 1-tap recording (which does not).
**Not fixed.** Teaching currently depends on pacing — roughly two seconds
between taps, without scrolling in between. `docs/pre-demo-checklist.md`
covers this. A durable fix would extend the raw-touch capture path to
cover the case rather than relying on the cached-screen window.

## 30 Sep 2026 — the credential gate costs app coverage (accepted trade)

`CredentialGateRules.classify()` runs before every replay step, and blocks
on payment/OTP/login wording. It is two-tier: strong signals (a password
field, "card number", "cvv", "upi pin", "add new card", …) block on a
single hit; weak signals ("pay", "upi", "wallet", "payment", …) need two
or more distinct matches, are matched on word boundaries, and are searched
only in on-screen text — never in resource ids or class names.

That design was itself a fix: the earlier version matched `"pay"` and
`"place order"` as bare substrings across text, ids *and* class names,
which blocked on the **cart** screen — the screen a flow must act on to
reach payment at all — and would have failed T2 while adding nothing to
T11.

The remaining cost is app coverage. **Amazon's home screen advertises
"Amazon Pay"**, which is enough to halt replay at step 1. The gate is
behaving exactly as specified; the specification is blunt. A screen that
merely *links* to payment is not a screen that *collects* payment details,
and we do not currently distinguish the two.

**Why we did not fix it on submission day:** T11 is worth 5 points and
carries a **−10** penalty for failure, a 15-point swing. Loosening the gate
to admit Amazon's home screen would have been an untested change to the
one component whose failure mode is worst. Refusing an app is recoverable;
tapping a real payment button is not.

**The right fix**, for after this cycle: classify by what a screen
*collects* rather than what it mentions — an editable field whose hint or
label names a card, CVV, UPI PIN or OTP, or `isPassword`, blocks; a
navigational affordance ("Amazon Pay", "Proceed to Pay", "Place Order")
does not, because the screen it leads to will block on its own terms.


## 30 Sep 2026 — two fields, one resource id

Zomato gives the global search bar and a restaurant's menu-search field the
same resource id (`…:id/edittext`), distinguished only by `indexInParent`
(0 vs 1). `NodeWalker.resolve`'s resourceId tier matched on the id alone and
discarded the index, so both matched and the first in traversal order won —
replay typed the item into the search box it had already used.
**What we do:** the resourceId tier consults `indexInParent` first and falls
back to id-only when the indexed lookup finds nothing, so it can only narrow
an ambiguous match, never lose one. Note that `indexInParent` is
*structural*, not semantic: a dynamic hierarchy can renumber it between
teaching and replay. The fallback is deliberate — it keeps a stale index
from turning into a spurious `Stuck` — but it means a stale index degrades
to the old ambiguous behaviour rather than failing loudly.

## 30 Sep 2026 — Swiggy is not a supported target

We evaluated Swiggy as an alternative food-ordering target. Its results
screen exposes **zero actionable elements** to the accessibility tree
(`Semantic snapshot: 0 actionable elements`), and a teaching session there
recorded four steps with no `OPEN_SEARCH`, `SEARCH_INPUT` or
`SUBMIT_SEARCH` role at all — the search interaction was never visible to
us. Swiggy is therefore **not** declared as a target app.

## 30 Sep 2026 — replay-side duplicate-CLICK guard removed

`ReplayPlanner` previously skipped a CLICK whose anchor matched the
immediately preceding step, on the assumption that one taught tap is always
exactly one `FlowStep`. That assumption is false: Zomato's search flow
requires two genuine taps on elements that both read "Domino's Pizza" (the
autocomplete suggestion, then the restaurant card), and the guard silently
dropped the second. `FlowStep` carries no timestamp, so the guard could
never distinguish "same tap captured twice" from "user genuinely tapped
twice" — it was structurally incapable of being correct. The artifact case
it existed for is handled at the source, at teach time, by
`TapDedup.isSameTap` and `TouchClaim.canClaim`, which do have timestamps.

**Test debt this created:** six unit tests across `ReplayPlannerTest` and
`SemanticReplayTest` asserted the removed behaviour and are marked
`@Ignore` with an explanatory message rather than rewritten. They should be
rewritten or deleted; they are listed here rather than left for a reader to
discover.


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

## 28 Sep 2026 (verification pass) — teach-session process-kill resume: core mechanism confirmed working; one new gap found

Verified on-device (Realme CPH2381, `integration-test` @ e1445ce), two separate teach -> kill -> resume -> continue -> finish cycles, using `run-as com.calo kill -9 <pid>` (plain `adb shell am kill` is a no-op against this service's process class -- it only targets killable/cached-tier processes, and an accessibility service's bound process isn't one; don't waste time re-testing with it).

**Confirmed fixed, closing the 25 Sep entry below for its original scope:** `TeachCheckpoint` persistence, step/targetPackage recovery, and `MainActivity` UI reconciliation all work correctly across a real process kill mid-teach. Checkpoint file matched actual taught steps exactly both runs (19/19, 5/5), the resume log line reported the correct recovered count both times, the UI correctly showed "Finish Teaching"/teaching-active immediately after resume (not "Start Teaching"), and the final saved flow's step order was intact across the kill boundary with no drops or duplicates. The 25 Sep entry's original failure mode (silent total session loss) is closed.

**New gap found, not yet fixed:** raw touch capture (`TouchInteractionController`, the Finding-6 fallback that recovers taps Android misclassifies as SCROLL instead of CLICK) registered cleanly after both resumes (`registered, initial state=STATE_CLEAR`) but delivered zero `onMotionEvent` callbacks after one of the two resumes -- confirmed against two separate real taps, 46s and 3+ minutes after that resume, both landing as `Dropped CLICK with no unclaimed finger touch behind it`. The other resume in the same session worked fine (`onMotionEvent` fired normally), so this is intermittent, not a guaranteed break -- but it's real, directly evidenced, and silent (no user-visible error; the recorder pipeline itself stays alive and keeps appending SCROLL steps normally, so nothing *looks* broken).

**Compounding risk:** Finding 6 (real taps landing as `TYPE_VIEW_SCROLLED` instead of `TYPE_VIEW_CLICKED`) is still live as of tonight -- reproduced during this same verification pass on Zomato. Raw touch capture is the safety net for exactly that misclassification. A demo session that (a) gets process-killed mid-teach, (b) resumes into this touch-capture-silently-dead state, and (c) then hits Finding 6 on a real tap will silently lose that tap with nothing telling anyone it happened.

**Decision (28 Sep, time-constrained before freeze):** documenting rather than fixing tonight. This is a narrow, three-condition compound failure in an already-fragile subsystem (see this file's own history of touch-capture races); a rushed fix here risks trading a known, low-probability, silent failure for a new, unknown one, and reliable reproduction wasn't achieved even across two attempts tonight (1/2). Revisit post-freeze with dedicated reproduction time, not as a last-minute patch.

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
