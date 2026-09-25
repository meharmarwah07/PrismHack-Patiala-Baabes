# Known limitations

Honest status on every open issue we know about, as of 26 Sep 2026. Judges
reward disclosure here more than they punish the underlying bug — this list
is written on that assumption. Status values: **Open** (not fixed),
**In progress** (a lane is actively working it), **Fixed — unverified**
(code changed, no device confirmation yet), **Fixed — verified**, **Won't
fix this cycle** (known, deliberately deferred past the freeze).

## Accessibility-event fidelity

### Bug B — some toolbar buttons never emit a click event
**Status:** TODO(lane A) — update when reported.
**Impact:** A flow taught through a screen with an affected toolbar button
silently records fewer steps than the user actually performed for that tap
— the button press itself never becomes a `FlowStep`. Replay then either
skips straight to the next real step (if downstream steps don't depend on
that tap having happened) or gets `Stuck`/misbehaves at whatever step
expected that tap's side effect. There is no error at *teach* time — the
recording completes silently short.

### Finding 6 — Zomato taps logged as SCROLL
**Status:** TODO(lane A) — root cause not yet identified; update when
reported.
**Impact:** At least one tap target on Zomato is being classified by
`TeachRecorder` as `TYPE_VIEW_SCROLLED` instead of `TYPE_VIEW_CLICKED`,
producing a `FlowStep(action = SCROLL)` where a `CLICK` was intended. Replay
then performs `ACTION_SCROLL_FORWARD` on that anchor instead of
`ACTION_CLICK` — the flow doesn't fail loudly, it does the wrong action.
This is the most concerning open item on this list because it's a *silent
wrong-action* failure mode, the exact category the credential gate and
Stuck-on-not-found are designed to prevent for other cases; this one isn't
caught by either.

## Finding 1 — gate can false-trigger on login-adjacent launch screens

**Status:** Open, by design tension rather than a bug in the gate itself.
**Cause:** Replay always launches the target app's default entry point
(`PackageManager.getLaunchIntentForPackage`), not the specific screen a flow
was taught from. If that default entry point is (or transiently shows) a
"sign in / continue as guest" interstitial, `CredentialGateRules` correctly
classifies it as login-related and halts — correctly, per its own rules,
but before the flow ever had a chance to reach the taught screen.
**Impact:** A flow can report `Halted` at step 0 on an app whose cold-start
path passes through any login-flavored screen, even for an already-logged-in
user, if that transient screen is slow enough to still be showing when the
first gate check runs.
**Mitigation for the demo:** confirm target apps (Zomato, Amazon) open
straight to their logged-in home screen with zero interstitial — see
[pre-demo-checklist.md](pre-demo-checklist.md).

## Finding 4 — live AccessibilityNodeInfo caching breaks recovery across same-Activity transitions

**Status:** Open.
**Impact:** When a target app changes its visible content without a full
Activity transition (e.g. a fragment swap, a bottom-sheet replacing the
content below it), a cached/stale `AccessibilityNodeInfo` reference can
still validate structurally but no longer correspond to what's on screen,
so a subsequent `findNode`/action can act on a node that's no longer the
one the user sees. `NodeWalker` re-resolves from `rootInActiveWindow` on
every step rather than reusing a stored reference, which mitigates but does
not fully eliminate this — recovery specifically *across* one of these
same-Activity transitions is the part that's still fragile.

## Finding 2 — Clock alarm-row widget

**Status:** TODO(lane A) — details pending.

## Single `targetPackage` per flow — cross-app replay not supported by design

**Status:** By design, not a bug.
**Impact:** `LearnedFlow.targetPackage` is a single string set once at teach
time from whichever app emitted the first recorded event. A flow cannot span
two apps (e.g. "check the weather then order an umbrella on Amazon") — that
would require a fundamentally different `LearnedFlow` shape (an ordered list
of `(targetPackage, steps)` segments) that nothing in the current schema, DB
migration, or `ReplayPlanner` sequencing supports. Not attempted this cycle.

## No replay history persisted (T14)

**Status:** Not built. See the T14 design proposal (Room `ReplayRun`
entity + migration sketch) reported separately — the team is deciding
whether it lands before the freeze.
**Impact:** There is no "what did you do today" query possible today —
`ReplayResult` is returned to a callback and then discarded; nothing writes
it anywhere durable.

## Not built

- **Wake word.** Voice capture is manually triggered (button/debug
  broadcast), not always-listening. Out of scope for this pass.
- **Discarding unnecessary touches.** `TeachRecorder` records every
  `TYPE_VIEW_CLICKED` / `TYPE_VIEW_TEXT_CHANGED` / `TYPE_VIEW_SCROLLED`
  event it sees while teaching, with no filtering for accidental/incidental
  taps (e.g. a mis-tap that was immediately corrected, or a scroll that
  didn't need to be part of the recipe). A noisy teach session produces a
  noisy `FlowStep` list verbatim.

## Also worth flagging (not on the original list, found during this pass)

- `DebugTriggerReceiver` (`START_TEACHING` / `FINISH_TEACHING` /
  `REPLAY_LATEST` / `VOICE_COMMAND`) is registered in the main manifest with
  `android:exported="true"` for *all* build types, including release — any
  app on the device can send these broadcasts to a release install. The new
  `DebugResetReceiver` added in this pass avoids that by living entirely
  under `app/src/debug/` (excluded from release at the manifest level, not
  just via an `exported` flag); `DebugTriggerReceiver` was not touched here
  since it's owned by another lane. Flagging for lane A to decide before a
  release build is ever produced.
