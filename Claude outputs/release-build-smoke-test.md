# Calo — release-build smoke test (still not done, top priority before freeze)

Branch: `integration-test` @ `3005c76` (push it first if not already done — `git push origin integration-test`). Deadline 30 Sep, feature freeze 29 Sep. Fresh session, read this whole prompt first. This has been flagged three separate times tonight and never actually executed — it's the single biggest remaining unverified item, because judging happens on a release build and nothing tonight has run one.

## What's already known (don't re-investigate, verify instead)
- `app/build.gradle.kts` reads `GROQ_API_KEY` from `local.properties` directly (fixed 24 Sep, documented in the file's own comment) and sets it via `buildConfigField` under `defaultConfig` — this applies identically to debug and release, so it *should* carry over correctly. Confirm this empirically, don't just trust the comment.
- `buildTypes.release` only sets `signingConfig = signingConfigs.getByName("debug")` — no minification/shrinking enabled. So this isn't a typical "release build behaves differently due to R8" risk; the main unknowns are the API key actually reaching a real HTTP call, and anything accidentally gated on `BuildConfig.DEBUG` that shouldn't be.
- `pm clear com.calo` does not work on this device (Realme CPH2381, ColorOS — OEM restriction, confirmed earlier tonight). Don't waste time on it. Use `adb shell run-as com.calo kill -9 <pid>` if you need to simulate a process kill for any reason, or just uninstall/reinstall for a clean state.

## Task
1. **Search for anything gated on `BuildConfig.DEBUG` that isn't just logging** — grep the codebase (`grep -rn "BuildConfig.DEBUG" app/src`). Confirm every hit is a diagnostic log line being compiled out, not a functional code path (e.g. don't let something like "only register X in debug" silently disable a needed feature in release). Report anything that isn't purely logging.
2. **Build the actual release APK**: `.\gradlew.bat assembleRelease` from your PowerShell (this sandbox can't build — Mehar or you need to run this on the real machine; if you're a device-connected session, run it directly).
3. **Install fresh**: uninstall any existing `com.calo` install first (`adb uninstall com.calo`), then `adb install app/build/outputs/apk/release/app-release.apk`.
4. **Confirm it launches** without crashing, accessibility service can be enabled (Settings → Accessibility — same manual-enable step needed on this ColorOS build as tonight's debug builds), and mic permission can be granted.
5. **Confirm the actual NLU path works** — this is the real point of the test. Teach a simple flow (doesn't need to be the full Zomato/SUBMIT_SEARCH one — anything with a couple of real taps is enough to prove the pipeline), then trigger replay by voice. Watch logcat for `NLUClient` output — confirm it's NOT logging `GROQ_API_KEY is not set` and that a real match happens (not a guaranteed-miss due to a blank key).
6. **Confirm CredentialGate still fires correctly** on this build too — quick check (navigate to any payment/OTP screen), don't skip it just because it was verified on debug earlier.

## What a failure looks like
- `NLUClient: GROQ_API_KEY is not set` in logcat on the release build — means the key isn't actually making it through for this variant despite the code looking correct, and needs root-causing before freeze, not just re-confirming the comment is accurate.
- Crash on launch, on enabling accessibility, or anywhere in the teach/replay flow that doesn't happen on debug — points at something genuinely different about the release variant (signing, resource handling, a `BuildConfig.DEBUG`-gated functional path from step 1).
- Accessibility service or mic permission failing to grant/persist differently than on debug.

## Report format
1. `BuildConfig.DEBUG` grep results — confirm all logging-only, or flag anything functional.
2. `assembleRelease` succeeded: yes/no, any warnings worth noting.
3. Install + launch: clean or not.
4. NLU path: confirmed working with a real match (not a key-missing guaranteed-miss) — show the actual logcat evidence, not just "it worked."
5. CredentialGate: confirmed firing on this build.
6. Overall verdict: is the release build safe to submit as-is, or is there a real gap that needs fixing before freeze.
