# Calo — build status (18 Sep 2026)

Native Android/Kotlin, package `com.calo`. Theme 3, "Teachable Voice
Automation." This README is the honest status report — what's real and
verified, what's written but unverified, and exactly why.

## Why this README is unusually blunt about verification

An earlier session's context got corrupted: it claimed `CredentialGate`,
`ReplayEngine`, and `NLUClient` were already built and device-tested, when
none of that code existed anywhere. That mistake doesn't get repeated
here — every claim below says exactly how it was checked.

## Layout

```
domain/   pure Kotlin/JVM, ZERO Android imports — runs under plain `gradle test`
app/      the actual Android app — AccessibilityService, Room, SpeechRecognizer, networking
```

`:domain` holds the model classes and every decision-making rule: the
credential gate, slot substitution, replay sequencing, and the NLU
prompt/response logic. `:app` holds everything that has to touch a real
Android API — AccessibilityNodeInfo, Room, SpeechRecognizer, OkHttp — and
is a thin adapter over `:domain`, on purpose: the split is what makes the
safety-critical logic testable without a device at all.

## What's REAL and verified — actually run in this build pass

37 JUnit tests, actually compiled and executed against a hand-assembled
Kotlin+JUnit toolchain (this sandbox has no internet access to Maven
Central, so Gradle itself couldn't run here — see below). All 37 pass:

- `CredentialGateRulesTest` — including the T11 case: an unreadable
  screen fails closed (Blocked, never Clear), a password field blocks
  regardless of bland surrounding text, OTP/payment/login keyword
  detection, and two regression guards against false positives (a plain
  numeric quantity field, an ordinary menu screen).
- `ReplayPlannerTest` — including the specific guarantee that was never
  previously verified: given a flow that walks into a payment screen
  mid-replay, replay halts at that exact step with **zero taps or
  text-entry attempted from that step onward** (asserted directly against
  a fake screen provider's call log, not inferred). Also covers
  element-not-found → Stuck, `ACTION_SET_TEXT` returning `false` → Stuck
  (not silently treated as success), and slot substitution overriding the
  recorded literal.
- `SlotResolverTest`, `NluPromptTest`, `NluResponseParserTest` — the
  slot-fallback logic (T2 exact-replay vs. T4–T6 generalized replay) and
  the Groq response parser's defensive handling of markdown fences,
  missing fields, out-of-range confidence, and outright garbage replies.

Run it yourself once you have internet + JDK 17: `./gradlew :domain:test`

The app module's Kotlin (`NodeWalker`, `CredentialGate`, `ReplayEngine`,
`CaloAccessibilityService`, `TeachRecorder`, `NLUClient`, `VoiceInputManager`,
`CaloOrchestrator`, the Room data layer) was additionally compiled — for
real, with the same local toolchain — against hand-written stub versions
of the Android, Room, and OkHttp APIs matching their documented public
signatures. That compile succeeded and caught one real gap (this
environment's stub was initially missing `AccessibilityNodeInfo.packageName`).
**Be clear about what this does and doesn't prove**: it confirms the code
is internally consistent, every interface is implemented with matching
signatures, and there are no typos/import errors — it does NOT prove real
Android runtime behavior, because it's checked against stubs I wrote from
memory of the documented API, not the real android.jar or Room's real
annotation-processor codegen.

## What's written but UNVERIFIED — needs a real device/emulator

No Android SDK, emulator, or device exists in the environment this was
built in, so none of this has run for real. In priority order:

1. **The credential gate, on a real screen.** `ReplayPlannerTest` proves
   the *sequencing* is correct against a fake screen provider. It does
   NOT prove `CredentialGate`'s real `AccessibilityNodeInfo`-tree walk
   recognizes an actual payment/OTP/login screen's real node tree the
   same way the fake did. Test this first: teach a flow up to (not past)
   a real payment screen, hit Replay, confirm zero taps happen.
2. **`ACTION_SET_TEXT` on a real field.** Some custom widgets silently
   don't support it; `performAction` returning `false` correctly surfaces
   as `Stuck` in the code, but that path has never fired against a real
   widget.
3. **Room codegen.** `kapt` needs to actually generate `FlowDao_Impl` —
   untested here since kapt requires the Android Gradle Plugin, which
   requires network access this sandbox doesn't have.
4. **SpeechRecognizer + RECORD_AUDIO.** No mic, no permission-request UI
   wired (that's a UI concern, out of scope for this pass).
5. **The Groq call itself.** `NLUClient` is written to Groq's documented
   OpenAI-compatible endpoint (`https://api.groq.com/openai/v1/chat/completions`)
   with model `llama-3.1-8b-instant` (current as of Sep 2026 per
   console.groq.com/docs/models — re-check before the demo, Groq's model
   lineup changes). Never called with a live key in this pass.

## Explicitly out of scope for this pass (by request)

- All UI — no screens, no layouts, no theme. The manifest uses the
  platform default theme so it's valid without any `res/` design work.
- Wiring a "start teaching" / "stop teaching" trigger to any actual
  button or voice phrase — `CaloOrchestrator.startTeaching()` /
  `finishTeaching()` exist and are ready to be called by whatever UI or
  voice command ends up driving them.

  (Both bullets above are stale as of the 23 Sep 2026 pass below — a real
  home screen and a real slot-promotion UI now exist. Left in place rather
  than deleted so this file still reads as a record of what was true when
  it was written; see the dated section for current state.)

## Slot-promotion UI (23 Sep 2026)

Before this pass, every taught flow saved with `slots = emptyList()`
unconditionally — `TeachRecorder.promoteToSlot()` existed but nothing
called it, so replay was always a literal replay, never a generalized one
(T4/T5/T6/T9 all depend on this). Fixed:

- **`CaloOrchestrator` split in two.** `stopTeaching()` can only be called
  once per teaching session (it nulls out the service's recorder), which
  made the old single-method `finishTeaching()` (stop + save in one call)
  unusable for a flow that needs a review step in between. `finishTeaching()`
  is now the debug-only path (`DebugTriggerReceiver`'s adb trigger still
  uses it, unchanged behavior, still saves with zero slots — that's the
  intentional "exact replay" debug path SlotResolver's own comment calls
  T2, not a regression). A new `saveTaughtFlow(steps, targetPackage, slots,
  triggerUtterance, description, onSaved)` takes already-finalized state and
  only does the `repository.save()` — it never calls `stopTeaching()` itself.
- **`MainActivity`** now owns the single `stopTeaching()` call
  (`beginFinishTeaching()`), gets the live `TeachRecorder`, and — only if
  at least one recorded step has something nameable (a `SET_TEXT`'s
  `recordedValue`, or a `CLICK` whose target had visible `text`) — shows a
  new review dialog (`dialog_promote_slots.xml`, rows built at runtime from
  `dialog_promote_slot_row.xml`, since the app module has no RecyclerView
  dependency and the row count isn't known until teaching ends). Each row
  lets the user type any slot name or leave it blank; naming one calls
  `recorder.promoteToSlot(stepOrder, slotName)` directly against that same
  recorder instance. The existing trigger-phrase/description dialog
  (`dialog_finish_teaching.xml`, unchanged) runs after, and its Save button
  now builds `List<SlotDefinition>` from whatever got promoted and calls
  `saveTaughtFlow()` with real steps + real slots.
- **Nothing recorded to promote** (T1/T2/T8 — e.g. only clicks on unlabeled
  icons) skips the review dialog entirely and goes straight to the
  trigger/description dialog, so those flows still save correctly with
  zero slots.
- Canceling at any point (review dialog or trigger dialog) saves nothing.
  Teaching was already stopped by `beginFinishTeaching()`'s one
  `stopTeaching()` call before either dialog appears, so cancel never needs
  to touch the service again — no risk of the double-stop bug this split
  was designed to avoid.
- **Design decision forced by `SlotDefinition`'s real fields**: promoting a
  `CLICK` step (per the task spec: "a CLICK whose target had text") records
  a `slotName` on that `FlowStep` and produces a `SlotDefinition` for it.
  ~~At the time this section was first written, that slot had no effect on
  replay~~ — **fixed in the 23 Sep 2026 pass below; CLICK slots now
  generalize too.** Left the strikethrough in rather than deleting it so
  this stays an accurate record of what was true at each point, per this
  README's own stated policy above.
- `SlotDefinition.producedByStepIndex` is populated with the promoted
  step's `FlowStep.order` (the field is currently unused anywhere except as
  debugging context, per its own doc comment, so this is a reasonable
  reading of "step index" — `order` is already this codebase's unique,
  1-based identifier for a step).

**What was actually verified this pass:** `.\gradlew.bat :app:compileDebugKotlin`
and `.\gradlew.bat :app:assembleDebug` both `BUILD SUCCESSFUL`, using the
*real* Android Gradle Plugin, real `android.jar`, and real Room `kapt`
codegen (Temurin 17 + a generated Gradle wrapper now exist in this repo —
see below; the "no Android SDK in this sandbox" limitation the rest of this
README describes no longer applies as of 19 Sep 2026). That's a real
compiled, packaged debug APK, stronger than the stub-based compile
described elsewhere in this file. `:domain:test` was re-run and all
existing tests still pass unchanged (nothing in `:domain` was touched).
**What's still unverified: the actual device behavior.** The APK was built
but not installed or launched — I did not teach a real flow, tap through
the new review dialog, confirm the rows render correctly for a real
multi-step recording, or confirm a promoted slot round-trips through Room's
real JSON `Converters` and comes back out with `slotName` intact. Test this
next, in this order: teach a flow with at least one `SET_TEXT` step (e.g.
typing a pizza name), confirm the review dialog lists it, promote it to
`item`, save, then say a different item name by voice and confirm replay
substitutes it instead of the recorded literal.

## CLICK slots now generalize too (23 Sep 2026, second pass)

The gap flagged above — a promoted `CLICK` step saved a `slotName` and a
`SlotDefinition`, but replay ignored it and just re-tapped whatever was
recorded at teach time — was a **silent-wrong-action bug**, not a crash: a
flow taught tapping "Home" and promoted to an `address` slot would look
like it worked (`Completed`, no error) while actually tapping "Home" again
even when the caller asked for "Work". Fixed:

- **`SlotResolver.resolveClickTarget(step, slotValues)`** (new function;
  `resolveValue()` itself is untouched, still `SET_TEXT`-only, exactly as
  before). Mirrors `resolveValue`'s own fallback philosophy but for the
  "what should we search for" question instead of "what should we type":
  no slotName → `null` (not a slot step). SlotName present but no value
  supplied → `null` (T2 exact replay — re-tap the taught anchor,
  unchanged). SlotName present, value supplied, but it's IDENTICAL to what
  was recorded (`target.text` or `target.contentDescription`) → `null` —
  the taught anchor already points at the right element, no need for the
  alternate path. Only when the supplied value genuinely **differs** from
  what was recorded does it return that value — the one case where
  re-resolving the original anchor would tap the wrong, stale element.
- **`NodeProvider.findNodeByValue(value)`** (new method on the `:domain`
  interface `ReplayPlanner` talks to) and **`ReplayPlanner`'s `CLICK`
  branch**: when `resolveClickTarget` returns non-null, replay calls
  `findNodeByValue` instead of the ordinary `findNode(step.target)`, and a
  miss is `Stuck` with a reason naming the value that couldn't be found
  (`"couldn't find an option matching 'Work'"`) — it never falls back to
  tapping the stale original element. Same fail-closed philosophy
  `CredentialGate` and the rest of `ReplayPlanner` already use elsewhere.
- **`NodeWalker.findByValue(root, value)`** (new method, `:app`) and
  **`ReplayEngine.findNodeByValue()`** wire the domain-level decision to a
  real `AccessibilityNodeInfo` search: text first, then contentDescription
  — the same two tiers `resolve()` already uses for a taught anchor, just
  matched against the new slot value instead of the anchor's own recorded
  fields. No resourceId/className tier here on purpose: there's no taught
  resourceId for a value nobody ever tapped during teaching, so
  text/contentDescription (what a person would actually read to find
  "Work" themselves) is genuinely all there is to search by.
- `SET_TEXT`'s code path — `SlotResolver.resolveValue()`,
  `provider.findNode(step.target)` — was not touched. A regression test
  (`SET_TEXT slot substitution is unaffected by the CLICK slot-search fix`
  in `ReplayPlannerTest`) runs a `SET_TEXT` slot substitution and a `CLICK`
  slot substitution in the same flow and checks both independently.

**What was verified:** 6 new tests in `SlotResolverTest` (all four branches
of `resolveClickTarget`'s fallback logic, plus a contentDescription-only
anchor case) and 5 new tests in `ReplayPlannerTest` (new value found →
taps it and never touches the stale anchor; no matching option → `Stuck`,
zero taps attempted at all, not even the wrong one; value equal to
recorded → normal anchor path; no value supplied → normal anchor path
unchanged; `SET_TEXT` + `CLICK` slots in the same flow both resolve
independently). `:domain:test` — all 48 tests pass (`SlotResolverTest`: 11,
`ReplayPlannerTest`: 12). `:app:assembleDebug` — `BUILD SUCCESSFUL` against
the real AGP/Room toolchain (see above), confirming `NodeWalker` and
`ReplayEngine`'s new methods compile against the real
`AccessibilityNodeInfo` API, not just the domain-level fake.
**What's still unverified:** `NodeWalker.findByValue()` has never run
against a real accessibility tree — `ReplayPlannerTest` proves the
*sequencing* decision (search-by-value vs. re-tap vs. Stuck) against a fake
`NodeProvider`; it does not prove the real tree walk actually finds "Work"
in a real app's real node tree the way the fake does. Test this on-device
once a build exists: teach a flow tapping one option from a list (e.g. a
saved-address picker), promote that `CLICK` step to a slot, replay with a
*different* option's name via voice, and confirm it taps the new option —
then confirm replaying with an option name that isn't on screen halts
`Stuck` with a message naming it, rather than tapping anything.

## Three teach-time capture bugs, fixed (23 Sep 2026, third pass)

A prior claim that these three were already fixed and device-verified
turned out to have zero trace in the repo — a repo-wide search for
`beforeText` found nothing. Don't trust that claim; here's what was
actually found by reading `TeachRecorder.kt` and
`CaloAccessibilityService.kt` fresh, and what was actually done about it.

**1. Self-recording the teach-start tap — CONFIRMED, fixed.**
`TeachRecorder.onAccessibilityEvent()` recorded every CLICK/SET_TEXT/SCROLL
event it received with no filter on which app it came from. The tap on
Calo's own "Teach a Flow" button — and any of Calo's own subsequent UI
changes (e.g. the pill's label flipping to "Finish Teaching") — fires
AccessibilityEvents the same as any app under test, so any of those could
become step 1 of the recorded flow, or a spurious step partway through.
Worse: `targetPackage` is only ever set once (`if (targetPackage == null)`),
so if the FIRST captured event happened to be Calo's own UI, `targetPackage`
would latch to Calo's own package for the entire flow — not just one wrong
step, but every subsequent step on the real target app resolving against
the wrong app. Fixed by giving `TeachRecorder` a required
`ownPackageName: String` constructor parameter, checked first thing in
`onAccessibilityEvent()`: `if (event.packageName?.toString() ==
ownPackageName) return`. `CaloAccessibilityService.startTeaching()` passes
its own `packageName` (the real `Context` property, install-time-accurate
— not the hardcoded literal `"com.calo"` the task flagged as the thing to
avoid). This is real Android glue (`event.packageName`, `Context.
packageName`) — left in `:app`, not domain-testable in any meaningful way
beyond a plain string-equality check.

**2. SET_TEXT anchor built from post-edit text — CONFIRMED, fixed.**
`anchorFor()`'s `text` field was always `node.text?.toString()`, called on
the SAME node that just received the SET_TEXT event — i.e. what was just
TYPED (e.g. "Margherita"), not what the field looked like before. At
replay time, `NodeWalker.resolve()`'s text tier searches the live screen
for a node whose text equals the anchor's `text` — but before this step
runs, the field doesn't contain "Margherita" yet (that's the whole point
of the step), so that tier would never match on any field without a
`resourceId` to match on first. Fixed by capturing
`event.beforeText?.toString()` (the field's PRE-edit state) instead, but
only for the SET_TEXT case — `anchorFor()` gained an optional `text`
parameter defaulting to the old `node.text?.toString()` behavior (still
correct for CLICK/SCROLL, where the node's label IS stable at tap time).
A blank `beforeText` (the common case — most fields start empty) is
dropped to `null` rather than kept as `""`: an empty string is a weak,
ambiguous match key (several blank fields could share it), so instead this
leans on `NodeWalker`'s own existing contentDescription → className+index
fallback tiers, exactly as an anchor with no text at all already does —
no parallel matching rule invented here. `event.beforeText` access is real
Android API surface, so this stayed in `:app`; the "which text counts as
a useful anchor" trimming logic is trivial enough (one `takeIf`) that it
wasn't worth extracting into `:domain` on its own.

**Caveat on #2 — FIXED (23 Sep 2026, fourth pass).** The gap above (one
SET_TEXT `FlowStep` per keystroke, so only the first keystroke's
`beforeText` was ever a genuinely stable anchor) is closed. New `:domain`
function `TextEntryCoalescer.isSameFieldEntry(newAnchor, lastTextEntryAnchor)`
decides whether an incoming `TYPE_VIEW_TEXT_CHANGED` event belongs to the
SAME field entry as the immediately-preceding SET_TEXT step. Deliberately
**not** a time-based debounce like `ScrollCoalescer` — a real pause
mid-word (thinking, or just typing slowly) can easily exceed a fixed
window while still being the same entry, so time is the wrong signal here.
Instead it compares NODE IDENTITY: the two anchors' `resourceId`/
`contentDescription`/`className`+`indexInParent`, with `text` explicitly
excluded from the comparison (`anchor.copy(text = null) == other.copy(text
= null)`) since `text` is exactly the field guaranteed to change every
keystroke — reusing `ElementAnchor`'s existing structural-equality
`data class`, not a new identity scheme. `TeachRecorder` tracks
`lastTextEntryAnchor` + `lastTextEntryStepIndex` (the `steps` index of the
step currently absorbing keystrokes); a same-field event now does
`steps[index] = steps[index].copy(recordedValue = typed)` — **replacing**
the value in place — instead of appending, and only the FIRST keystroke of
a new entry builds the stored anchor from `event.beforeText` (subsequent
keystrokes update `recordedValue` only, never touch `target`). A CLICK or
SCROLL event resets both tracking fields, so a later TEXT_CHANGED on the
same field after an intervening tap correctly starts a new entry rather
than reopening the old one (matches this task's explicit spec: "only start
a new step when... a different event type arrives").

**What was verified:** `TextEntryCoalescerTest` (new, 6 cases — no prior
entry never coalesces; same `resourceId` coalesces despite differing
`text`; different `resourceId` doesn't; the `className`+`indexInParent`
fallback tier coalesces/doesn't-coalesce the same way when there's no
`resourceId`; and explicitly, an anchor from a 10th keystroke (`"hello
worl"`) still coalesces against the 1st keystroke's anchor (`""`),
regardless of how much the text has grown). `:domain:test` — **59/59**
pass (was 53; +6). `:app:assembleDebug` — `BUILD SUCCESSFUL`, confirming
`TeachRecorder`'s new state fields and the `TextEntryCoalescer` call
compile against the real `AccessibilityEvent` API. Per this project's
existing test-architecture boundary (no Android/AccessibilityEvent mocking
infra exists anywhere in `:app` — confirmed zero files under
`app/src/*Test*`; `NodeProvider`'s own doc comment says outright
"AccessibilityNodeInfo can't run outside an actual Android process"), the
DECISION logic (`TextEntryCoalescer.isSameFieldEntry`) is what's real
unit-tested; `TeachRecorder`'s integration of it — actually mutating
`steps` correctly across a real sequence of `AccessibilityEvent`s — is
verified only by compilation, same as every other Android-facing line in
this file always has been in this project.
**Still unverified, and this is a capture-time bug fix so say it plainly:**
never run against a real keystroke stream. Test on-device before trusting
it: type a multi-character value into a real field (ideally one with no
`resourceId`), then check the saved flow's step count in Database
Inspector — should be exactly one SET_TEXT step for that field, with
`recordedValue` equal to the FULL typed string, not a truncated
in-progress fragment; tap elsewhere, then type into the SAME field again,
and confirm THAT produces a second, separate step rather than reopening
the first.

**3. Stray SCROLL steps from one continuous gesture — CONFIRMED, fixed.**
`TYPE_VIEW_SCROLLED` was recorded unconditionally, one FlowStep per event,
with no coalescing — a single finger-drag scroll fires many of these in
quick succession, so one real scroll during teaching would pollute the
flow with a run of near-duplicate SCROLL steps, each of which replay
would try to reproduce as a separate action. Fixed with a new
`ScrollCoalescer.shouldCoalesce(newAnchor, lastScrollAnchor, lastScrollAtMs,
nowMs)` — pure logic, zero Android dependency, so it's a real `:domain`
function with its own test (`ScrollCoalescerTest`, 5 cases), not just
inline `:app` code. `TeachRecorder` tracks the anchor and timestamp of the
last-recorded SCROLL step; a new scroll event on the SAME anchor within
**300ms** of the last one extends that window instead of adding a step —
outside the window, or on a different anchor, it's a new step. 300ms was
picked because it's comfortably longer than the gap between events within
one continuous drag (which fire far faster than a finger can pause
mid-gesture) but short enough that two deliberately separate scrolls
(scroll, look around, scroll again) still produce two distinct steps.

**What was verified:** `ScrollCoalescerTest` (new, 5 tests: no prior
scroll, same-anchor-within-window, exact boundary is inclusive,
same-anchor-past-window is a new burst, different-anchor never coalesces)
plus the existing 48 — `:domain:test` is 53/53 green.
`:app:assembleDebug` — `BUILD SUCCESSFUL` against the real AGP/Room
toolchain, confirming `TeachRecorder`'s new constructor param, the
`event.beforeText` access, and the `ScrollCoalescer` call all compile
against the real `AccessibilityEvent`/`AccessibilityNodeInfo` API.
**What's still unverified, and matters more than usual for this pass:**
these are capture-time bugs — a clean build proves the code is wired up
correctly, it proves NOTHING about whether the fix actually behaves right
against a real, live stream of AccessibilityEvents, which is exactly the
kind of thing a compile can't catch (the previous claimed-fixed-but-wasn't
history on this exact file is the reason to take that seriously here).
Before trusting this, re-test teaching on a real device:
  - Tap "Teach a Flow", then immediately check the first recorded step —
    confirm it's the first real action in the TARGET app, not a tap on
    Calo's own UI, and confirm `targetPackage` is the target app's package,
    not `com.calo`.
  - Record a SET_TEXT into a field that has no `resourceId` (a lot of
    custom/web-view-backed apps have these) — confirm replay can still
    find and type into it after this fix, where it likely couldn't before.
  - Record a deliberate scroll gesture (one continuous drag) — confirm it
    produces ONE SCROLL step, not a dozen, by checking the saved flow's
    step count (e.g. via the SQLite `learned_flows` table or a debug log).

## CLICK capture fixed for a null `event.source` (23 Sep 2026, on-device verification pass)

On-device verification of the four passes above (this README's own "still
unverified" caveats, finally acted on) found a real bug in a place none of
the unit tests could reach: teaching a single tap on a Settings homepage row
("Battery") produced a flow whose ONLY step was a `SCROLL` on
`recycler_view` — no `CLICK` at all — even though the tap demonstrably
worked (the screen navigated to `SubSettings`). Reproduced 3 for 3 across
two different rows before being root-caused.

**Root cause, confirmed via temporary diagnostic logging, not guessed:**
```
D Calo: RAW type=TYPE_VIEW_CLICKED pkg=com.android.settings cls=android.widget.LinearLayout hasSource=false text=[Battery, 100%] beforeText=null cd=null
W Calo: DROPPED(source null) type=TYPE_VIEW_CLICKED pkg=com.android.settings cls=android.widget.LinearLayout
```
The CLICK event fires with entirely correct content (right package, right
class, right text) but `event.source` is `null`. This is **not** the
occasional flake a 2026-09-19 finding described (seen "once" on a Contacts
FAB) — it reproduced deterministically (3/3) for Settings'
Preference/RecyclerView-based rows, most likely because such rows are
virtual accessibility nodes (`AccessibilityNodeProvider`-backed) rather than
one real `View` per row, and `event.source` doesn't resolve a virtual node
the same way. `TeachRecorder`'s old `val source = event.source ?: return`
silently discarded these — the only thing left to record was whatever
incidental SCROLL followed navigation (a pre-existing, separate,
already-documented gap — see "Three teach-time capture bugs" above;
`ScrollCoalescer` only coalesces *bursts*, it doesn't filter a single
incidental scroll, and that's still true after this pass and out of scope
for it).

**Fix, in two parts:**

1. **`NodeWalker.findAllByValue`** — one shared text/contentDescription
   search, used by BOTH this fix and the pre-existing replay path
   (`SlotResolver.resolveClickTarget` → `NodeWalker.findByValue`, which is
   now built on top of it) so the matching rules can't drift between the
   two call sites. Every match is resolved to its **nearest clickable
   ancestor** first (`nearestClickableAncestor`) — a text match is very
   often a label INSIDE the actionable row (a Preference row's title
   `TextView`, confirmed non-clickable in the real UI dump) rather than the
   actionable element itself, and `ACTION_CLICK` silently fails on a
   non-clickable node. This is a bonus fix for the EXISTING replay path
   too, not just the new one.

2. **`TeachRecorder`'s `lastGoodRoot` cache.** The first working version of
   this fix (matching `event.text` against THIS event's own `currentRoot`)
   **compiled clean and still failed on-device** — confirmed via a second
   round of diagnostic logging:
   ```
   D Calo: DIAG recordClickWithoutSource: currentRoot=true rootPkg=com.android.settings rootClass=android.widget.FrameLayout segments=[Battery, 100%] visibleTexts=[]
   ```
   By the time `CaloAccessibilityService.onAccessibilityEvent` reads
   `rootInActiveWindow`, the OS can have already flipped the active window
   to the destination screen (`SubSettings`), which hadn't finished
   inflating yet — an empty `FrameLayout` stub, zero visible text. Neither
   the old screen (no longer "active") nor the new one (not yet populated)
   had anything to search. Fixed by having `TeachRecorder` cache the most
   recent screen that looked genuinely populated (`lastGoodRoot`,
   `refreshGoodRootCache`), refreshed opportunistically on every qualifying
   event — including event types previously ignored entirely, like
   `TYPE_WINDOW_STATE_CHANGED` — and seeded eagerly the moment teaching
   starts (`CaloAccessibilityService.startTeaching()` → `TeachRecorder
   .seedRoot()`), since the very first event of a session can itself be a
   null-source CLICK with nothing else to fall back on.

   The cache is guarded, not trusted blindly:
   - **"Real" screen check** (`countVisibleTextNodes` >
     `MIN_VISIBLE_TEXT_NODES_FOR_REAL_ROOT`, currently >1) — rejects the
     empty in-transition stub instead of caching it.
   - **Staleness guard**: refuses (drops + logs) a cached root older than
     `MAX_GOOD_ROOT_AGE_MS` (2000ms) rather than silently matching against
     a screen from several taps ago.
   - **Cross-app/cross-navigation guard**: refuses (drops + logs) if the
     cached root's package doesn't equal the CURRENT event's own
     `event.packageName` — readable even when `source`/`currentRoot` are
     not — rather than matching a Settings-homepage snapshot against a
     click that's actually now in some other app.

   Ambiguity (a text segment matches more than one on-screen element) is
   handled by trying `event.className` as a disambiguating signal first;
   if that doesn't narrow it to exactly one, every candidate is logged and
   the first in traversal order is used deterministically — never a silent
   coin-flip, since a capture-time mismatch has no later "Stuck" safety net
   the way replay does.

**Two DISTINCT staleness risks — deliberately not blurred into one caveat:**

- **(a) Cache staleness (this pass's cache, milliseconds-scale, within ONE
  capture moment):** `lastGoodRoot` can be up to `MAX_GOOD_ROOT_AGE_MS`
  (2000ms) old relative to the click being processed. If a screen change
  that invalidates the match happens within that window (rare in practice —
  screen transitions settle in well under a second even on a slow device —
  but not impossible), the recovered anchor could describe a row that
  changed or scrolled out of position between the cached snapshot and the
  actual tap. The staleness guard bounds the RISK, it doesn't eliminate it.
- **(b) Text-match staleness (pre-existing to this whole approach,
  days/sessions-scale, between TEACH time and REPLAY time):** matching by
  visible text at all — whether via this recovery path or the pre-existing
  `SlotResolver.resolveClickTarget` replay path — is fragile if that text
  changes between when a flow is taught and when it's replayed: a battery
  percentage, a price, a rating, a timestamp. `recordClickWithoutSource`
  tries the FIRST `event.text` segment first specifically because it's
  usually the more stable one (a row's title vs. its summary), but a title
  isn't guaranteed stable either. This is a real, structural limitation of
  text-based matching, not a bug this pass introduced or could fully close.

**What was verified, with real command output (not paraphrased) — same
Battery-row repro used to find the bug, re-run after the fix:**
```
=== Calo log ===
09-23 21:59:53  I Calo: Teaching started...
09-23 21:59:55  I Calo: Teaching finished. saved=true trigger="cachefix1"
=== steps in DB ===
[{"order":1,"action":"CLICK","target":{"className":"android.widget.LinearLayout","indexInParent":4}},
 {"order":2,"action":"SCROLL","target":{"resourceId":"com.android.settings:id/recycler_view", ...}}]
```
Step 1 is now `CLICK` with `indexInParent=4` — the exact position of the
Battery row among the homepage's clickable rows, confirmed against a fresh
`uiautomator dump` taken before teaching (`Network & internet`=0,
`Connected devices`=1, `Apps`=2, `Notifications`=3, `Battery`=4). No
"Dropped"/"Ambiguous" log lines this run — the "Battery" segment matched
exactly one element, no disambiguation needed. Step 2's SCROLL is the
already-documented, unrelated incidental-scroll gap, unchanged by this
pass. `:domain:test` — 59/59, unaffected (this fix is entirely in `:app`;
`NodeWalker`/`TeachRecorder` have no domain-side counterpart to test).
**Still unverified:** this fix's ambiguity path (multiple same-text
candidates on one screen) and its staleness/package guards actually
triggering correctly have NOT been exercised on-device — only the
single-match happy path has real evidence behind it.

## Hybrid replay: exact recording + semantic layer (27 Sep 2026)

Calo used to learn *the app*: each step was "tap this resource id / this
text", so a flow only ever ran on the app it was taught on. It now also
records what each step *means*, and uses that as a fallback on the same
app and as the only path on a different app.

- **Roles.** `FlowStep` gained `role: SemanticRole?` + `roleIndex`
  (`OPEN_SEARCH`, `SEARCH_INPUT`, `SUBMIT_SEARCH`, `SELECT_RESULT`,
  `ADD_TO_CART`, `BUY_NOW`, `GO_TO_CART`, `CHECKOUT`), and `ElementAnchor`
  gained `hintText`. Both default to null, so flows already in Room decode
  unchanged. `RoleLabeler` assigns roles at save time (and lazily at replay
  for old flows) with deterministic keyword + sequence rules, not an LLM.
  A step that fits no rule keeps `role = null`.
- **Two replay modes** (`ReplayPlanner`, `ReplayMode`):
  - `EXACT` (same app): taught anchor first, exactly as before. Only if it
    isn't found does a step with a role fall back to `RoleMatcher`.
  - `SEMANTIC` (different app): only steps with a role or slot run, each
    grounded by `RoleMatcher` on a `ScreenElement` snapshot
    (`NodeWalker.snapshot`). Steps with no known meaning are skipped. A flow
    with no roles at all is refused up front.
  - `RoleMatcher` returns `Ambiguous` instead of guessing (e.g. two "ADD"
    buttons in a list), which becomes `Stuck` with "not sure what to tap".
  - The credential gate still runs before every step in both modes, and
    again after a pop-up is dismissed.
- **Pop-ups.** On a miss, one attempt to tap a whole-label dismiss button
  ("Not now", "Skip", "Close", …; never "Allow"/"Cancel"/"OK"), then retry.
- **Search submit.** When no suggestion row matches, replay presses the
  keyboard's action key (`ACTION_IME_ENTER`, Android 11+) and falls back to a
  search button.
- **Screen waits.** The fixed 400ms sleep is gone. After every action,
  `ReplayEngine` waits until two reads 250ms apart show the same screen (max
  4s), and `findNode` retries for up to 1.5s. Replay now runs off the main
  thread.
- **Picking the app.** The NLU prompt lists each flow's app and extracts a
  `targetApp` ("…on Myntra"). The orchestrator resolves it to an installed
  package by launcher label; a different package from the taught one means
  `SEMANTIC` mode.
- **Confidence.** A match below 0.6 now asks "Did you mean …?" in a dialog
  before anything is tapped. The adb `VOICE_COMMAND` path refuses unless
  `--ez confirm true` is passed.
- **Teach survives a process kill.** `TeachCheckpoint` writes the steps to
  `filesDir` on every change. When the service reconnects in a new process
  within 30 min, teaching resumes with the recovered steps, and
  `MainActivity.onResume` syncs the button. This addresses the 25 Sep
  "teaching does not survive a normal-length session" finding.

**What was verified:** `:domain:test` gives 129/129 (was 87). The 42 new
tests are `RoleLabelerTest`, `RoleMatcherTest` (includes `PopupRules`),
`SemanticReplayTest` (an Amazon-taught flow replayed on a Myntra-like
screen, exact-mode never touching the matcher, gate halts in both modes,
gate re-check after pop-up dismissal, ambiguity → Stuck with zero taps)
and NLU `targetApp` parsing/prompt. All existing tests pass unchanged.
`:app:assembleDebug` gives `BUILD SUCCESSFUL` against the real toolchain.
**Still unverified on a device:** all of the `:app` side. That covers
`NodeWalker.snapshot` against real Amazon/Myntra trees (the labels and
`inList` detection the matcher depends on), `ACTION_IME_ENTER` on real
search fields, the settle-wait timings, `resolvePackageForAppName` finding
Myntra by label, the confirm dialog, and checkpoint resume after a real
process kill. Test in that order: teach "search wireless earbuds and add the
first result to cart" on Amazon, `DUMP_FLOWS` to confirm the roles, replay
it on Amazon, then say "…on Myntra".

## Fixed: replay never waited for the screen to settle between steps (29 Sep 2026)

"The recorded flow does not run completely" — every real taught flow with
more than one screen transition failed almost immediately. Root-caused and
fixed this session, both confirmed on-device (Realme, ColorOS, `com.calo`
build installed 29 Sep 00:26), not just by re-reading code.

**Root cause:** `NodeProvider.awaitScreenChange()`'s own doc comment says
"Called after every performed action: wait for whatever it triggered to
finish drawing" — but `ReplayPlanner.replay()` never called it. The only
call to a settle-wait anywhere in the step loop was gated behind
`ActionType.WAIT`, and `TeachRecorder` never records a `WAIT` step (grep
confirms zero references), so every real taught flow went straight from a
successful `performClick`/`performSetText`/`performScroll`/`performImeEnter`
into the very next step's gate check and `findNode()` with no wait at all.
`ReplayEngine.waitForStableScreen()` (the 300ms-min/4000ms-max settle poll
this project's own comments and README's "Screen waits" section already
describe) only ever ran once, before step 1 — the documented per-step
behavior simply didn't exist in the code that runs it.

**Confirmed on-device before the fix, live logcat:** replaying a real taught
flow (open Zomato search → type "Dominos" → submit → tap a result) failed
at step 1 every time — `findNode()` for the search bar was attempted 145ms
after the previous step's own transition began, nowhere near enough time
for Zomato's UI to react.

**Fix:** `ReplayPlanner.replay()` now calls `provider.awaitScreenChange()`
(wrapped in the existing `STEP_TIMEOUT_MS` guard, not marked `isAction`)
immediately after any CLICK/SET_TEXT/SCROLL/SUBMIT_SEARCH action that
reports success, before moving on to the next step. `:domain:test` — all
existing tests pass unchanged (the fake `NodeProvider`'s `awaitScreenChange`
is the interface's own no-op default, so no test needed updating).

**Confirmed on-device after the fix, same flow, same device:** all three
steps that previously never ran now complete — `CLICK` opens search,
`SET_TEXT` types "Dominos", `SUBMIT_SEARCH` (`ACTION_IME_ENTER`) submits —
each with a real ~2s gap matching the screen's actual transition time. The
flow's 4th and final step then went `Stuck` looking for a specific search
result (`"Paneer Maxxx"`) that Zomato's live results for "Dominos" no longer
contain (confirmed by screenshot: current results are Domino's Pizza, Pizza
Hut, Oven Story Pizza, Domnik Pizza, Cheese Burst Factory — no "Paneer
Maxxx" anywhere) — real-world content drift between teach time and replay
time, the same structural risk this file's "Claims that don't match the
repo" / `KNOWN_LIMITATIONS.md`'s text-match-staleness section already
name, not a regression or a new bug. Replay correctly reported `Stuck`
with a clear reason instead of guessing.

**Separately confirmed, not fixed this pass (pre-existing, different bug):**
teaching a fresh flow via two `adb shell input tap` taps (open search, tap
a result) recorded neither `CLICK` — only the `SET_TEXT` and incidental
`SCROLL` events landed. This matches `KNOWN_LIMITATIONS.md`'s already-open
"`AccessibilityEvent.source == null` — confirmed still present, unhandled"
entry; whether synthetic `adb input tap` events specifically hit this same
gap (as opposed to only some real-touch cases) wasn't previously documented
and is worth a note for anyone using adb-driven taps to script teaching.

**Still open:** the two saved on-device flows both start with steps taught
weeks ago against Zomato's then-current UI/content — re-teaching them fresh
would give a cleaner end-to-end `Completed` demonstration than relying on
whatever search results happen to still exist. The fix itself is now
verified against the actual failure mode reported ("does not run
completely"), not just against unit tests.

## Three more "does not run completely" bugs, found and fixed chasing the one above (29 Sep 2026, same night)

Getting the settle-wait fix above to actually complete a real taught flow
surfaced three further real bugs, each found by reading logs/screenshots
from an actual failure, not guessed. All confirmed on-device, same
Realme/ColorOS device.

**1. A phantom `SUBMIT_SEARCH` step invented from an unresolved tap.**
`TeachRecorder.tryResolvePostTypingTap()` (`Finding 6 fallback, part 2`)
tries to resolve a tap made while the keyboard is up by matching pre-tap
candidate text against the new screen; `PostTypingTapResolver.Outcome
.Ambiguous` means several candidates matched — its own doc says this
"is evidence a tap happened", the opposite of "nothing happened". The
caller (`TeachRecorder.onAccessibilityEvent`'s `TYPE_WINDOW_STATE_CHANGED`
branch) treated `Ambiguous` exactly like `NoMatch`, falling through to
`recordSubmitAfterTyping()` either way — recording a `SUBMIT_SEARCH` step
that never happened. At replay, that step performs a REAL
`ACTION_IME_ENTER` submission, landing on a different screen than wherever
the real (unresolved) tap actually went, so the next taught step's anchor
— captured on that real, different destination — doesn't exist on the
`SUBMIT_SEARCH`'d screen and mis-resolves. Fixed: `tryResolvePostTypingTap`
now returns a `PostTypingTapAttempt` (`RESOLVED`/`NO_MATCH`/`AMBIGUOUS`/
`NOT_ATTEMPTED`); `recordSubmitAfterTyping` is skipped specifically for
`AMBIGUOUS`, logging instead of guessing. **Verified on-device**: re-taught
the same flow, `tryResolvePostTypingTap` now correctly committed a real
CLICK (`"Domino's Pizza"`) where it previously logged the ambiguous-match
warning and silently substituted a fake submit.

**2. `findNode`/`findNodeByValue` were always single-shot, contradicting
this README's own earlier claim** ("findNode retries for up to 1.5s") —
checked by reading `ReplayPlanner.kt` directly, that retry never existed.
Confirmed on-device: a live search-as-you-type screen renders in WAVES (an
early sparse state, e.g. `nodeCount=64`, can satisfy the settle-wait's own
stability check during a brief lull before the real content arrives), and
the very next step's one-shot `findNode` call can lose that race. Fixed:
`ReplayPlanner` now retries a null `findNode`/`findNodeByValue` result every
250ms for up to 1.5s before giving up, still bounded well inside the outer
5000ms `STEP_TIMEOUT_MS`. **Verified on-device**: the same step that
previously failed instantly now succeeds after the content finishes
loading, without changing anything about steps that were already fast.

**3. `NodeWalker.resolve()`'s last-resort className+indexInParent tier
had zero ambiguity protection**, unlike every tier above it (`resourceId`/
`text`/`contentDescription` all route through `pickAmong`, which refuses —
`Stuck`, not a guess — when several candidates can't be disambiguated by
`contextLabel`). The className+index tier used a plain `find()`: first
match in traversal order, full stop. Confirmed root cause of a real mis-tap
(a taught `layout_holder_menu_fab` FAB anchor, no text/contentDescription,
resolved to an unrelated `FrameLayout` on the replay-time screen; after
`performClick` "failed" on it, the coordinate-fallback tap landed on the
search bar). Fixed: this tier now goes through the same `pickAmong`
disambiguation as the others (a new `collectAllByClassNameAndIndex` +
`pickAmong` overload taking a pre-collected match list, since this tier
needs per-child index the plain-predicate `pickAmong` couldn't see).

**3b. Even after #3, a related but DISTINCT problem surfaced**:
`performAction(ACTION_CLICK)` can report `true` while doing nothing —
confirmed on-device, a climbed-to-ancestor's click "succeeded" and the
whole flow still reported `Done.`, while the app silently never left the
search-results screen. Some views handle taps via custom touch/gesture
logic instead of a real `OnClickListener`, and Android's accessibility
layer doesn't reliably distinguish "dispatched" from "actually did the
thing" for those. `NodeWalker.resolve()` now returns a `ResolvedMatch`
(node + `requiredClimb: Boolean` — true when the actually-matched node
wasn't itself clickable/editable/scrollable, so `actionable()` had to climb
to a clickable ancestor, a guess about which container owns the real click
behavior). `ReplayEngine.performClick()` now verifies the screen actually
started changing (reusing the same fingerprint mechanism `waitForStableScreen`
already had, refactored out as `waitForFingerprintChange`) before trusting
a climbed match's reported success — but ONLY for climbed matches, and
deliberately fails closed (`Stuck`) rather than retrying via the existing
coordinate-tap fallback: that fallback is for a click KNOWN to have failed
(`ok=false`); retrying here too would risk a genuine double-fire on some
OTHER click that legitimately doesn't change the screen (a toggle,
add-to-cart), which this heuristic can't tell apart from "climbed ancestor
did nothing". A direct hit (matched node already clickable) is trusted as
before, unchanged, so this adds zero extra latency to the common case.

**What was verified, all three/four together:** `:domain:test` passes
unchanged (the fix in #2 lives in `:domain` and is exercised by the
existing suite, just slower on the "genuinely not found" tests due to the
real retry delay — no test needed updating, since the fake `NodeProvider`'s
behavior per call didn't change, only how many times it's called).
`:app:assembleDebug` — `BUILD SUCCESSFUL`. On-device: the same real taught
flow that motivated the settle-wait fix now runs its `CLICK`→`SET_TEXT`→
`SUBMIT_SEARCH` sequence cleanly every time (previously always failed by
step 1), and a second, independently re-taught flow reached `Done.` twice
in separate runs. **Still open:** getting airtight proof of a "real"
`Completed` (not just no-crash/no-hang, but landing on the exact intended
screen) for THIS specific taught flow ran into two separate, pre-existing,
already-documented issues unrelated to tonight's fixes: (a) the target
restaurant's own live "accepting orders" status changed between test runs
(a real-world moving target, not a bug), and (b) raw touch capture
intermittently drops the search-bar tap entirely during teaching (this
file's own 28 Sep entry on `TouchInteractionController` silently going
dead already covers this). Re-verify end to end once those are addressed
or worked around; the specific bugs this pass targeted are fixed and
individually confirmed regardless.

## Setup once this reaches a real machine

1. Open in Android Studio (needs internet — this project's Gradle files
   pull Room/AndroidX/OkHttp/Kotlin from Maven Central + Google's Maven,
   neither reachable from the sandbox this was built in).
2. Set `GROQ_API_KEY` — either as a Gradle property in `local.properties`
   (`GROQ_API_KEY=...`) or an environment variable of the same name.
   Never commit it.
3. `./gradlew :domain:test` first — should pass with zero setup, proves
   the logic layer before touching anything device-dependent.
4. Enable the accessibility service (Settings → Accessibility → Calo)
   before testing teach/replay on-device.
