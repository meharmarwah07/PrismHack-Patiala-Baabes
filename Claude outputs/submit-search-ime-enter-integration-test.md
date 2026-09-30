# Calo — implement SUBMIT_SEARCH via ACTION_IME_ENTER, on integration-test

Branch: `integration-test`, currently at `f03f3f8`. Deadline 30 Sep, feature freeze 29 Sep. Fresh session, no memory of anything before this — read the whole prompt first. Work in an isolated worktree (`git worktree add .worktrees/submit-search integration-test`) rather than the shared checkout, so you don't collide with anything Mehar is doing directly in the main working copy.

## The problem
Some flows require submitting a search via the keyboard's Enter/Search key, with no separate tappable submit button (confirmed on Zomato — no such button exists). `TeachRecorder` already detects this and labels the step `SUBMIT_SEARCH` (a `SemanticRole`), but records it with a completely empty anchor — nothing to act on at replay time. Replay correctly goes `Stuck` when it hits this step, every time. This blocks the actual demo flow (Zomato pizza order).

## The fix — capture a semantic target, not a mystery step
Capture the identity of the **input field that was focused immediately before submission**, and at replay time resolve that same field and invoke `AccessibilityNodeInfo.ACTION_IME_ENTER` (API 30+ — exists specifically for "invoke the action associated with pressing enter/search while this field has IME focus"). This project's `minSdk = 26` (confirmed in `app/build.gradle.kts`), so the API-level gate below is a real, live code path, not a hypothetical.

### Teach time (`TeachRecorder`)
When `SUBMIT_SEARCH` is detected, capture the focused node via `findFocus(AccessibilityNodeInfo.FOCUS_INPUT)` and save its identity (`resourceId`, `className`, `text`/`hint`, `contentDescription`) as the step's target — same shape as an ordinary `ElementAnchor`, not a new format.

**Verify the timing empirically before assuming this works.** Instrument the actual sequence on Zomato: keyboard Search pressed → submission detected → `findFocus(FOCUS_INPUT)` called → log timestamp + whatever node identity comes back (or null). Determine whether the focused node is reliably still present at the moment `SUBMIT_SEARCH` fires, or whether it's sometimes already gone (field losing focus as results load). If unreliable at detection time, capture the input target from state immediately *before* submission is detected, not via a delayed tree search after.

### Replay time (`ReplayPlanner`/`ReplayEngine`)
The current `ReplayPlanner.replay()` loop (read it first — it's changed since this feature was originally scoped) already wraps every device-touching call in `withStepTimeout(stepOrder, opName, isAction = ..., block)`, and marks `isAction = true` only for calls that actually do something on the device (`performClick`/`performSetText`/`performScroll`) — `ReplayResult.Stuck.actionMayHaveExecuted` is derived from that flag, and `CaloOrchestrator.handleReplayResult()` uses it to refuse auto-retry on a timeout that might have already fired for real. **`performAction(ACTION_IME_ENTER)` is exactly this kind of call — it must go through `withStepTimeout(..., isAction = true) { ... }` like the other three, not bypass the wrapper.** Skipping this would silently reopen the hang/uncontrolled-late-action risk that was just fixed for the other three action types.

Ordering, do not reorder or skip a step:
```
SUBMIT_SEARCH
  → minSdk / API capability check
      < 30 → Stuck(IME_ENTER_UNSUPPORTED), no crash
      >= 30 → continue
  → resolve/verify target (same field identity match discipline as ordinary anchors — exact match, not fuzzy)
      not found / doesn't match → Stuck
  → CredentialGateRules.classify(...) via the existing withStepTimeout-wrapped currentScreenSignals() call —
      this already runs once per step at the top of the loop before your new branch; do not add a second,
      redundant gate check, and do not special-case SUBMIT_SEARCH out of it
  → verify node exposes ACTION_IME_ENTER (check its actionList — not all editable fields expose this
      even on API 30+, per Android's own docs: only when currently input-focused and editable)
      not exposed → Stuck(IME_ENTER_UNSUPPORTED)
  → withStepTimeout(step.order, "performImeEnter", isAction = true) { provider.performImeEnter(node) }
  → verify it actually succeeded (performAction's own return value)
      failed → Stuck, do not silently continue
  → next step
```

**Do not add a coordinate-guessing fallback if `ACTION_IME_ENTER` is unsupported or fails.** `Stuck(IME_ENTER_UNSUPPORTED)` is a legitimate, diagnosable failure — turning it into "tap where the Search key probably is" reintroduces exactly the wrong-action risk this app's replay design deliberately avoids everywhere else.

## Investigate first
Before implementing the full thing, spend a few minutes checking the actual Zomato search field during a teach session: is it currently input-focused and editable at the relevant moment, and does its `actionList` contain `ACTION_IME_ENTER`? If not, the clean route isn't available for this field — stop, don't force it, report the finding so the team can decide between re-scoping the demo flow or accepting the limitation, rather than shipping something shaky the night before freeze.

## Scope discipline
- Ordinary `CLICK`/`SET_TEXT`/`SCROLL`/`WAIT` replay is untouched.
- No migration for any previously-taught flows with empty-anchor `SUBMIT_SEARCH` steps — disposable, re-teach after this lands.
- `NodeProvider` will need a new method (e.g. `performImeEnter(node): Boolean`) — add it with a safe default (`false`) on the interface like the existing `screenElements()`/`nodeForElement()` pattern, so nothing else implementing `NodeProvider` breaks.
- Full JUnit suite must stay green.
- Small, reviewable diff — this lands close to freeze, not a broader refactor.

## Report format
1. Teach-time timing finding on Zomato: was the focused node reliably present at `SUBMIT_SEARCH` detection, or did you need to capture earlier?
2. Whether Zomato's search field actually exposes `ACTION_IME_ENTER` — the investigation result, not an assumption.
3. If supported: confirm the full replay ordering above, especially that `performImeEnter` goes through `withStepTimeout(..., isAction = true)` and that the gate isn't double-checked or skipped.
4. If not supported: what you found, and a recommendation (re-scope demo vs. document as known limitation) — don't force a workaround.
5. On-device verification: re-taught flow reaching `SUBMIT_SEARCH` and completing replay end-to-end (if supported), or clean `Stuck(IME_ENTER_UNSUPPORTED)` (if not) — either way, no crash, no hang.
6. Confirm the minSdk<30 path produces a clean `Stuck`, not a crash — say explicitly whether this was verified on a real old-API device or by reading the guard logic.
7. JUnit count before/after, commit hash(es), confirm branch `integration-test` (via the worktree) and that `git worktree remove` was done cleanly if you're finished.
