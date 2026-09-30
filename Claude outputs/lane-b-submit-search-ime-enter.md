# Lane B — follow-up (run AFTER the replay-hang timeout fix lands): SUBMIT_SEARCH via ACTION_IME_ENTER

You're on Lane B (`lane-b-replay`) on Calo (`com.calo`). Feature freeze 29 Sep. **Do this only after the replay-hang timeout fix (separate prompt, same session) is committed and verified — same branch, sequential, do not parallelize these two.** This task touches the same files (`ReplayEngine`, `ReplayPlanner`), so landing them one after another avoids conflicting with yourself.

## The problem
Some flows require submitting a search via the keyboard's Enter/Search key, with no separate tappable submit button (confirmed: Zomato's search has no such button). `TeachRecorder` already detects this and labels the step `SUBMIT_SEARCH`, but records it with a **completely empty anchor** — nothing to act on at replay time. Replay correctly goes `Stuck` when it hits this step, every time, with no path forward. This blocks any flow that needs it, including tonight's actual demo flow.

## The fix — capture a semantic target, not a mystery step
Instead of an anchorless `SUBMIT_SEARCH`, capture the identity of the **input field that was focused immediately before submission**, and at replay time, resolve that same field and invoke Android's purpose-built API for this: `AccessibilityNodeInfo.ACTION_IME_ENTER` (added API 30 — exists specifically for "invoke the action associated with pressing the enter/search key while this editable field has IME focus").

### Teach time (`TeachRecorder`)
When `SUBMIT_SEARCH` is detected, capture the focused node via `findFocus(AccessibilityNodeInfo.FOCUS_INPUT)` and save its identity (`resourceId`, `className`, `text`/`hint`, `contentDescription`) as the step's target — same shape as an ordinary `ElementAnchor`, not a new anchor format.

**Verify the timing empirically before assuming this works.** Instrument the actual sequence on Zomato: keyboard Search pressed → submission detected → `findFocus(FOCUS_INPUT)` called → log timestamp + whatever node identity comes back (or null). Determine whether the focused node is reliably still present at the moment `SUBMIT_SEARCH` fires, or whether it's sometimes already gone (field losing focus as results load). If it's unreliable at detection time, capture the input target from state immediately *before* submission is detected, not via a delayed tree search after.

### Replay time (`ReplayEngine`/`ReplayPlanner`)
Exact ordering, do not reorder or skip a step:
```
SUBMIT_SEARCH
  → minSdk / API capability check (project's minSdk = 26, confirmed — this is a real code path, not hypothetical)
      < 30 → Stuck(IME_ENTER_UNSUPPORTED), no crash
      >= 30 → continue
  → resolve/verify target (same field identity match discipline as ordinary anchors — exact match, not fuzzy)
      not found / doesn't match → Stuck
  → CredentialGate.check() — MANDATORY, same as every other step, no special-casing this step type out of it
      blocked → halt exactly like any other gated step
  → verify node exposes ACTION_IME_ENTER (check its actionList — not all editable fields expose this even on API 30+, per Android's own docs: only when currently input-focused and editable)
      not exposed → Stuck(IME_ENTER_UNSUPPORTED)
  → performAction(ACTION_IME_ENTER)
  → verify it actually succeeded (performAction's own return value)
      failed → Stuck, do not silently continue
  → next step
```

**Do not add a coordinate-guessing fallback if `ACTION_IME_ENTER` is unsupported or fails.** That would turn "Calo knows exactly what this action is" into "Calo is guessing where the Search key probably is" — exactly the kind of wrong-action risk the rest of this app's replay design deliberately avoids. `Stuck(IME_ENTER_UNSUPPORTED)` is a legitimate, diagnosable failure, not a gap to paper over.

## Investigate first, before writing the full implementation
Before implementing anything, spend a few minutes checking the actual Zomato search field during a teach session: is it currently input-focused and editable at the relevant moment, and does its `actionList` contain `ACTION_IME_ENTER`? If it doesn't, the clean route isn't available for this specific field — stop there, don't force it, and report that finding so the team can decide between re-scoping the demo flow or documenting the limitation, rather than shipping a shaky implementation the night before freeze.

## Scope discipline
- Ordinary `CLICK`/`SET_TEXT` replay is untouched — this only adds handling for the `SUBMIT_SEARCH` step type.
- No migration for tonight's already-taught flows with empty-anchor `SUBMIT_SEARCH` steps — those are disposable. Re-teach after this lands.
- `FlowStep`'s model change (adding a target to `SUBMIT_SEARCH`) is shared between teach and replay — since you're doing both sides in one session on one branch, there's no cross-lane merge to coordinate, but keep the change itself minimal and consistent with the existing `ElementAnchor` shape rather than inventing a parallel structure.
- Full JUnit suite must stay green.

## Report format
1. Teach-time timing finding: was the focused node reliably present at `SUBMIT_SEARCH` detection, or did you need to capture earlier? What you found on Zomato specifically.
2. Whether Zomato's search field actually exposes `ACTION_IME_ENTER` — the investigation result, not an assumption.
3. If supported: confirm the full replay ordering above was implemented as specified, especially that `CredentialGate` runs unconditionally before the IME action and that there's no coordinate-fallback path.
4. If not supported: what you found, and recommend re-scoping the demo vs. documenting as a known limitation — don't force a workaround.
5. On-device verification: re-taught flow reaching `SUBMIT_SEARCH` and completing replay end-to-end (if supported), or clean `Stuck(IME_ENTER_UNSUPPORTED)` (if not) — either way, no crash.
6. Confirm minSdk<30 path produces a clean `Stuck`, not a crash (can be verified by reading the guard logic even without a physical old-API device, but say explicitly which).
7. JUnit count before/after, commit hash, confirm branch `lane-b-replay` and that this landed sequentially after the hang-timeout fix.
