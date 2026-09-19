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
- Slot promotion UI (marking a taught literal value as a `{slot}`).
  `TeachRecorder.promoteToSlot()` is the hook a review screen would call;
  nothing calls it yet, so every taught flow currently saves with zero
  slots until that UI exists.
- Wiring a "start teaching" / "stop teaching" trigger to any actual
  button or voice phrase — `CaloOrchestrator.startTeaching()` /
  `finishTeaching()` exist and are ready to be called by whatever UI or
  voice command ends up driving them.

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
