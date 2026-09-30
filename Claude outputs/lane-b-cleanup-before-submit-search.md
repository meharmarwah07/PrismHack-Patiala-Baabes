# Lane B — two small fixes before SUBMIT_SEARCH (same session, same branch)

Do these first, then move to the `SUBMIT_SEARCH` prompt. Both are small — don't let either balloon in scope tonight.

## 1. `isDuplicateClick` on this branch is stale — fix it
`lane-b-replay` diverged from `integration-test` before a dedup fix landed there (`99d4eaa`). Your branch still has:
```kotlin
private fun isDuplicateClick(step: FlowStep, previous: FlowStep?): Boolean =
    step.action == ActionType.CLICK &&
        previous?.action == ActionType.CLICK &&
        previous.target == step.target
```
`previous.target == step.target` uses `ElementAnchor`'s full data-class equality, including `contextLabel` (captured live from surrounding screen text — can legitimately differ between two taps on the same element moments apart) and `hintText` (documented as not used to re-find the element at all). Replace with identity-field comparison only:
```kotlin
private fun isDuplicateClick(step: FlowStep, previous: FlowStep?): Boolean =
    step.action == ActionType.CLICK &&
        previous?.action == ActionType.CLICK &&
        previous.target.resourceId == step.target.resourceId &&
        previous.target.text == step.target.text &&
        previous.target.contentDescription == step.target.contentDescription &&
        previous.target.className == step.target.className &&
        previous.target.indexInParent == step.target.indexInParent
```
This avoids a conflict (and possible silent regression) when this branch eventually merges back into `integration-test`.

## 2. Timed-out actions can still fire for real, later, unsupervised — mitigate + document
`withStepTimeout`'s `future.cancel(true)` is best-effort against a blocked Binder call (correctly acknowledged in your own comment). If the call that timed out was `performClick`/`performSetText`/`performScroll` — an action call, not a read-only query like `currentScreenSignals`/`findNode`/`awaitIdle` — the real action can still execute on the device after `ReplayPlanner` has already returned `Stuck` and the user has been told "stuck, retry or stop." That's worse than a hang in one respect: it's a real, uncontrolled action landing with nobody watching, possibly after the user already said "stop."

There's no clean fix for this before freeze — you can't reliably cancel a blocked native IPC call. Do the cheap part instead:
1. Tag the `Stuck` reason differently when the timeout occurred on an action call vs. a query call (e.g. prefix the message, or add a boolean to `ReplayResult.Stuck` if that's a small enough change — your call on the least invasive way to carry this one bit of information).
2. In `CaloOrchestrator.kt`'s Stuck-question handling, when the timeout was on an action call, don't offer "retry" — retrying risks a double-fire if the original delayed call eventually lands. Steer to "stop" with a message acknowledging uncertainty (e.g. "the last action may not have completed cleanly").
3. Add this to `KNOWN_LIMITATIONS.md` regardless of what you implement for #1/#2 above: a timed-out action step cannot be guaranteed cancelled, and may execute after Calo has already reported it Stuck.

If differentiating action-vs-query timeout messaging turns out to be more than a few lines, skip it and just do the `KNOWN_LIMITATIONS.md` entry — don't let this eat time that `SUBMIT_SEARCH` needs.

## Report format
1. Confirm `isDuplicateClick` fixed, matches the 5-field comparison above.
2. What you did for the timeout-messaging mitigation (or confirm you skipped it and just documented instead, and why).
3. `KNOWN_LIMITATIONS.md` entry added — confirm text.
4. JUnit count, commit hash.
