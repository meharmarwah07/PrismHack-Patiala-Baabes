# Calo architecture

Native Android/Kotlin, package `com.calo`, Samsung PRISM Theme 3 —
Teachable Voice Automation. This document describes what the code actually
does, not what it's supposed to eventually do; TODOs below mark the specific
gaps other lanes are closing before the 29 Sep freeze.

## Pipeline

```
 TEACH                    STORE                 MATCH                REPLAY
┌─────────────┐      ┌───────────────┐    ┌──────────────┐    ┌──────────────────┐
│Accessibility│      │ Room:         │    │ Groq chat     │    │ ReplayEngine      │
│events while │─────▶│ learned_flows │───▶│ completions   │───▶│  + ReplayPlanner  │
│TEACHING     │      │ (LearnedFlow) │    │ (text-only,   │    │  + CredentialGate │
│(TeachRecorder)     │               │    │  all flows    │    │  (before EVERY    │
└─────────────┘      └───────────────┘    │  for this app)│    │   step)           │
                                           └──────────────┘    └────────┬──────────┘
                                                                        │
                                                         ┌──────────────┴──────────────┐
                                                         │                             │
                                                    ReplayResult              ReplayResult
                                                    .Completed /              .Halted (gate)
                                                    or                        or .Stuck
                                                    "ask a specific
                                                     question"
```

- **Teach**: `CaloAccessibilityService` forwards every `AccessibilityEvent`
  while `mode == TEACHING` to `TeachRecorder`, which turns CLICK /
  SET_TEXT / SCROLL events into an ordered `FlowStep` list, each anchored by
  an `ElementAnchor` (resourceId, text, contentDescription, className,
  indexInParent — see below). `CaloOrchestrator.finishTeaching()` wraps that
  step list plus the spoken trigger utterance into a `LearnedFlow` and saves
  it via `FlowRepository`, tagged with a single fixed `targetPackage` taken
  from whichever app emitted the first recorded event.
- **Store**: Room, one table (`learned_flows`), one entity (`LearnedFlow`).
  `FlowStep` and `SlotDefinition` lists are stored as JSON blobs via
  `Converters` — see `LearnedFlow.kt`'s own comment: this is the single
  handoff object between teach and replay, and the thing to agree on first
  if those two areas are being built in parallel.
- **Match**: `CaloOrchestrator.handleUtterance()` loads every saved flow
  (`FlowRepository.all()` — not scoped to the foreground app, since the user
  may speak a command before the target app is even open), builds a
  `CandidateFlow` per flow, and sends them plus the utterance to
  `NLUClient.match()`. That's one Groq chat-completions call
  (`openai/gpt-oss-20b`, `temperature=0.0`, text-only — no screenshot, no
  vision input) that returns a `matchedFlowId`, `slotValues`, and a
  `confidence`. Prompt construction and response parsing are pure functions
  in `:domain` (`NluPrompt`, `NluResponseParser`) so they're unit-tested
  without a network call; `NLUClient` is the thin OkHttp transport around
  them, and it collapses every failure mode — bad key, HTTP error, malformed
  JSON, markdown-fenced reply, genuine no-match — to the same
  `MatchResult(matchedFlowId = null)` shape, so the orchestrator only ever
  has one "didn't work" case to handle.
- **Replay**: if the matched flow's `targetPackage` isn't already in the
  foreground, `CaloOrchestrator` launches it via
  `PackageManager.getLaunchIntentForPackage` and polls
  (`launchAndWaitForForeground`) until the accessibility service reports that
  package as current, or times out. Then `ReplayEngine` (in `:app`) hands the
  step list and slot values to `ReplayPlanner` (in `:domain`, zero Android
  imports, fully unit-tested), which walks the steps in order, re-resolves
  each one's `ElementAnchor` against the *live* screen via `NodeWalker`, and
  dispatches the corresponding `AccessibilityAction`. No network call happens
  anywhere in this path — matching is the only place Groq is involved.
- **Gate / Stuck**: see below.

## Why anchor-based deterministic replay, not LLM/vision taps

This is the load-bearing design decision, so it's stated plainly rather than
hedged:

1. **Determinism (T2)**: `ReplayPlanner.replay()` is a pure function over a
   step list, slot values, and a `NodeProvider` — same inputs, same sequence
   of actions, every time. An exact-utterance replay of a flow taught five
   minutes ago must reproduce the same taps in the same order; a model
   re-deciding "what to tap" from a live screenshot on every replay cannot
   give that guarantee, because its output is a distribution, not a
   deterministic mapping from (flow, screen) to action.
2. **Fail-closed safety (T11)**: `CredentialGateRules.classify()` is
   evaluated by `ReplayPlanner` before *every* step, not once at flow start,
   specifically because a flow can navigate into a login/payment/OTP screen
   mid-replay that didn't exist when it was taught (session timeout, "add a
   card" mid-checkout). This is plain keyword/flag matching over
   `AccessibilityNodeInfo` text, resource IDs, class names, and
   `isPassword` — auditable by reading it, and it fails closed on an
   unreadable screen (`readable = false` → `Blocked`, never `Clear`). A
   vision-model tap-picker has no equivalent hard guarantee: it can fail
   *open* onto a pay button if the model misreads the screenshot, and
   there's no way to prove in advance that it won't, the way
   `CredentialGateRulesTest` proves this gate's classification for every
   case in its suite.
3. **No network during replay**: `ReplayEngine`/`ReplayPlanner` never call
   out to Groq or anywhere else — every action after the initial NLU match
   is resolved locally against the live node tree. This matters for latency
   (a multi-step flow doesn't wait on N round-trips) and for the security
   story: nothing about *what gets tapped* passes through a third-party API
   after teaching.
4. **Text-only model**: `NLUClient` sends text (the utterance + candidate
   flow descriptions), never a screenshot. Combined with (1) and (2), the
   LLM is structurally incapable of influencing *which element gets
   touched* — it only ever chooses *which already-taught flow* to run and
   *what slot values* to substitute into it.

## Why the LLM is confined to language understanding

Groq's `openai/gpt-oss-20b` does exactly two things and nothing else:
picking the best-matching `LearnedFlow` for a spoken utterance, and
extracting slot values (e.g. "the usual, but a Farmhouse instead" →
`{item: "Farmhouse"}`) from that utterance. It never sees the screen, never
picks a UI element, and never runs during replay. This is the same
determinism/safety argument as above, narrowed to one sentence: matching is
where ambiguity should live (paraphrase and slot generalization are
genuinely open-ended language problems worth an LLM), and replay is where it
must not (running the wrong action is a real-world side effect, not a wrong
answer that can be re-asked).

## The slot model

`SlotDefinition(name, exampleValue, producedByStepIndex)` marks a specific
taught `FlowStep` as one whose literal recorded value should be treated as a
variable. `FlowStep.slotName`, if non-null, tells `SlotResolver` to look up
that name in the NLU-extracted `slotValues` map at replay time; if the map
has no entry (T2 exact replay, or the NLU call silently failed to extract
one), it falls back to the originally recorded literal — the resolver never
substitutes an empty/missing value in silently. Slot promotion (marking a
taught literal as a slot after the fact) is a deliberate manual step —
`TeachRecorder.promoteToSlot()` is the hook — rather than inferred
automatically, on the same "don't guess" principle as the credential gate.

TODO(lane B): confirm end-to-end that slot extraction actually round-trips
through a live Groq call with the current prompt (`NluPrompt`) — verified so
far only against `NluPromptTest`'s fixtures and `NluResponseParser`'s
defensive parsing, not a live API response shape.

TODO(lane B/C): `MatchResult.confidence` exists and is parsed out of Groq's
response, but `CaloOrchestrator.handleUtterance()` currently does not check
it against any threshold before accepting a match — a low-confidence match
is treated identically to a high-confidence one. Decide and wire an actual
threshold (or an explicit design decision not to have one) before the
freeze.

## The gate design

Split deliberately across two files so the actual decision logic has exactly
one tested home:

- `CredentialGateRules` (`:domain`, unit-tested in
  `CredentialGateRulesTest`) — pure function `ScreenSignals -> GateVerdict`.
  Keyword-matches password/OTP/payment/login terms against all on-screen
  text, resource IDs, and class names, plus a direct
  `AccessibilityNodeInfo.isPassword` check. Fails closed: an unreadable
  screen classifies as `Blocked`, never `Clear`.
- `CredentialGate` (`:app`) — walks the current node tree exactly once per
  check into a `ScreenSignals`, then defers entirely to
  `CredentialGateRules`. It has no decision logic of its own, so there is no
  second, untested copy of "is this a payment screen" anywhere in the app.

`ReplayPlanner` calls this before every step (not once per flow) — see
"Why anchor-based deterministic replay" above for why that granularity is
the point.

TODO(lane A): Finding 1 — the gate can false-trigger on login-adjacent
launch screens (e.g. an app's "continue as guest / sign in" splash before
its real home screen), since replay always launches the target package's
default entry point rather than the specific screen a flow was taught from.
Root-cause and fix (or document as accepted behavior) before the freeze;
tracked in [known-limitations.md](known-limitations.md).

## The Stuck design

`ReplayResult` is a three-way sealed class: `Completed`, `Halted` (gate
fired — this step and everything after it was never attempted), and `Stuck`
(an anchor didn't resolve, or a resolved node's action returned `false`,
e.g. `ACTION_SET_TEXT` silently unsupported by a custom widget). Both
non-Completed cases carry `atStepOrder` and a `reason` string.

As implemented today, "Stuck" is a terminal status with a reason string —
`CaloOrchestrator.describeResult()` renders it as `"Got stuck at step N:
<reason>"`. The system summary's framing of Stuck as "asks the user a
specific question" is the intended UX, not yet the literal mechanism: the
reason string is written to be specific enough to phrase as a question
(e.g. `"element not found: ElementAnchor(text=Add to cart, ...)"`), but
turning that into an actual interactive prompt (rather than a one-way status
message) is a UI concern.

TODO(lane C): confirm the Stuck-state UI surfaces `reason` as an actual
question to the user (per the demo script's "forced stuck" segment) rather
than a raw status string, and that there's a way for the user to respond
(vs. just seeing that it failed).

## Known correctness gap tracked separately

Finding 6 (Zomato taps logged as SCROLL during teach) and Bug B (some
toolbar buttons never emit a click event at all) live in
[known-limitations.md](known-limitations.md) with current status — both are
accessibility-event-fidelity issues in the teach path (`TeachRecorder` /
`CaloAccessibilityService`), which this lane does not own.

TODO(lane A): root cause for Finding 6 — is it a genuinely ambiguous
gesture on Zomato's custom view, an event-type mapping bug in
`TeachRecorder`, or something Android's accessibility event dispatch does
for that specific widget class? Update this section once known.
