# Calo — full on-device verification of merged `main`, submission day

Branch: `main` @ `929ff77` (pull first: `git fetch origin && git checkout main && git pull origin main`). **Today is 30 Sep — submission day.** Fresh session, no memory of anything before this — read this whole prompt first, then work fast but don't skip the verification steps; a plausible-looking demo that hasn't actually been run end-to-end is the single biggest risk left.

## Why this prompt exists
`main` was assembled tonight/last night by merging four diverged feature branches (`lane-a-teach`, `lane-b-replay`, `lane-c-nlu`, `lane-d-submission`) plus a `submit-search` branch, on top of `integration-test`. The merge is committed and pushed, and `:domain:test` + `:app:assembleDebug` both build green — but that only proves it **compiles**. Nobody has run the actual merged code end-to-end on a device yet. Several of the merged pieces were hand-resolved conflicts (notably `CaloOrchestrator.kt`, which now combines cross-app SEMANTIC replay, the Stuck-question voice flow, ambiguity resolution (T13), and missing-slot prompting (T14) into one path for the first time) — this is exactly the kind of change that can compile clean and still be wrong at runtime. This prompt's whole point is catching that before it's the judges catching it.

## What's already known (don't re-investigate, verify instead)
- `pm clear com.calo` doesn't work on this device (Realme CPH2381, ColorOS, OEM restriction). Use `adb shell am broadcast -a com.calo.debug.DEBUG_RESET -p com.calo` to wipe the flow DB instead — note the `.debug.` segment, a bare `com.calo.DEBUG_RESET` silently no-ops.
- Target apps are Zomato (T1–T7, ordering a Domino's margherita) and Amazon (T8–T9). No pre-baked flows — judges teach live, so every flow you use tonight should be freshly taught, not one sitting in the DB from earlier sessions.
- `GROQ_API_KEY` is read from `local.properties`; confirm it's actually set before you start (`NLUClient: GROQ_API_KEY is not set` in logcat means every match is a guaranteed miss, not a real NLU failure).
- Logcat tags that matter: `Calo`, `CaloTouchCapture`, `NLUClient`, `CaloTapTiming`, `CaloCredentialGatePerf`. Use `adb logcat -v time Calo:D CaloTouchCapture:D NLUClient:D CaloTapTiming:D CaloCredentialGatePerf:D *:S`.

## Task

### 1. Clean baseline
- `git pull origin main`, confirm you're on `929ff77` or later.
- `.\gradlew.bat clean :domain:test` — confirm real execution (not "up-to-date"), all green.
- `.\gradlew.bat installDebug` on the real device.
- Wipe the flow DB via `DEBUG_RESET` (see above). Confirm empty (`getAll()` via whatever debug dump path exists, or just proceed and treat any unexpected flow in step 2 as a red flag).

### 2. Teach one real flow, cleanly
Teach the Zomato → Domino's → margherita pizza flow from scratch, from Zomato's actual home screen (not mid-navigation). Confirm via the flow dump afterward:
- No duplicate/near-duplicate consecutive CLICK steps (the `isDuplicateClick` fix should have caught these; if you see 2–3 near-identical steps in a row for one tap, that's a live regression, not a "minor cosmetic issue" — flag it immediately).
- The keyboard-search step recorded as a real `SUBMIT_SEARCH` with a populated anchor (not empty) — if it's `AMBIGUOUS`/`NO_MATCH` and silently invented a fake submit step, that's the exact bug `8802ffc` claims to have fixed; verify it didn't regress.

### 3. Exact replay (T2)
Trigger by voice with the exact taught phrase. Confirm: screen-settle waiting actually happens between steps (no immediate `findNode` failures right after a tap), `SUBMIT_SEARCH` fires a real `ACTION_IME_ENTER`, and the flow either reaches `Completed` or hits a **legitimate** `Stuck` (e.g. stale search results — a real-world content-drift issue, not a mechanism failure). If it hits Stuck, note exactly which step and why, and whether the spoken Stuck-question actually names the right element.

### 4. Paraphrase replay (T3)
Same flow, different wording than taught. Confirm NLU still matches (watch the `NLU match:` log line for `matchedFlowId`/`confidence`/`targetApp`), and that the "Thinking about that..." cue actually repeats (`"Still thinking..."`) if the Groq call runs past ~3s — this was flagged as unconfirmed in every prior session tonight; today is the day to actually confirm it, or explicitly report it didn't fire.

### 5. Slot-change replay (T4/T5/T6 — mandatory for the demo video)
Same flow, but with one slot value changed from what was taught (e.g. a different pizza item, if that's the taught slot) via voice. Confirm the new value actually gets used, not silently ignored or falling back to the taught value.

### 6. Ambiguity (T13) — new tonight, never verified on-device
Teach a **second** flow that's plausibly confusable with the first (similar trigger phrase). Trigger with an utterance that should land near the confidence tie (`NluMatchEvaluator.AMBIGUITY_MARGIN = 0.15`, `AMBIGUITY_FLOOR = 0.35`). Confirm Calo actually asks "Did you mean X or Y?" by voice, and that answering resolves to the right flow and replays it (via `proceedAsMatched`, not a separate simplified path). This is new, hand-merged code from tonight — treat a failure here as expected-until-proven-otherwise, not surprising.

### 7. Missing-slot prompting (T14 bonus) — also new, also never verified on-device
Trigger a flow by an utterance that omits a slot value entirely. Confirm Calo asks "Which \<slot\>?" and, once answered, actually replays with that value.

### 8. Stuck-question (mandatory for demo video)
Force a genuine Stuck (e.g. reference a UI element that's since changed, or interrupt mid-flow) and confirm the spoken question names a specific element, and that answering "stop" halts cleanly while a substitute value triggers a real retry from that step — not from the beginning.

### 9. CredentialGate (T11 — fail = -10, don't skip this)
During replay, navigate to Zomato's real payment/OTP screen. Confirm replay halts with `Stopped for your safety` and does **not** proceed, even after a stuck-question retry.

### 10. Cross-app SEMANTIC replay — brand new merged code, sanity-check only
If time allows: name a different app in the trigger utterance ("...on Amazon" for a Zomato-taught flow, or similar) and confirm it either attempts SEMANTIC-mode replay sensibly or reports a clear "I don't know how to carry this over" message — not a crash. This is the least-tested path in tonight's merge; a clean failure is an acceptable outcome here, a crash is not.

## What a failure looks like (don't rationalize it as success)
- Any regression in behavior that was previously confirmed working in earlier sessions (duplicate-click dedup, screen-settle waiting, SUBMIT_SEARCH, Stuck-question, CredentialGate) — these were all independently verified before tonight's merge; a regression means the merge resolution was wrong somewhere, not that the original fix was flaky.
- A crash anywhere, especially in the new ambiguity/missing-slot/cross-app paths.
- `CredentialGate` failing to halt on a real payment/OTP screen — top priority, report immediately if this happens.
- The "Thinking..." cue never actually repeating on a genuinely slow call.

## Report format
1. Build/install: clean or not, any surprises.
2. Teach: duplicate-step check, SUBMIT_SEARCH anchor populated or not.
3. T2 exact replay: Completed / legitimate Stuck / regression — which, and evidence.
4. T3 paraphrase: matched or not, "Thinking..."/"Still thinking..." cue confirmed or not.
5. T4–T6 slot change: new value honored or not.
6. T13 ambiguity: confirmed working, confirmed broken, or not reached — be explicit either way, this is new code.
7. T14 missing slot: same.
8. Stuck-question: element named correctly, stop/retry both confirmed.
9. T11 CredentialGate: confirmed halting on the real payment screen.
10. Cross-app SEMANTIC: attempted or skipped, clean failure or crash.
11. Overall verdict: is `main` as merged safe to submit as-is, and if not, exactly what needs fixing before you run out of today.
