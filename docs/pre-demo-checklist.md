# Pre-demo checklist

Run this in order, right before any judging or recording session. Every step
exists because it bit us once during rehearsal — this is not boilerplate.

## 1. Wipe the flow library

Every taught flow from every rehearsal is sitting in the Room DB. If you skip
this, the NLU match step sees stale/duplicate candidates (multiple "order a
Margherita" flows from different takes) and confidence gets noisy.

**Preferred: debug-build reset broadcast** (clears rows, keeps the app
installed, accessibility grant, and mic permission intact):

```sh
adb -s acd41a26 shell am broadcast -a com.calo.DEBUG_RESET -p com.calo
```

Confirm it worked:

```sh
adb -s acd41a26 logcat -s CaloDebugReset
```

You should see a line like `learned_flows reset: 4 row(s) -> 0 row(s)`. This
receiver (`DebugResetReceiver`) only exists in debug builds — see
[architecture.md](architecture.md) — so this command is a no-op (broadcast
goes nowhere) against a release build.

**Brute-force alternative: `adb shell pm clear com.calo`**

```sh
adb -s acd41a26 shell pm clear com.calo
```

This also wipes the Room DB, but it wipes *everything else app-scoped too*:

- The accessibility-service grant (Settings → Accessibility → Calo flips back
  to off — replay and teach both silently do nothing until it's re-enabled).
- The RECORD_AUDIO runtime permission (next mic use re-prompts).
- Any other granted runtime permissions.
- BuildConfig.GROQ_API_KEY is unaffected (baked in at build time, not app
  data), but if you also reinstall the APK you obviously start fresh there
  too.

Use `pm clear` only if the debug broadcast path is unavailable (e.g. testing
a release build) or something is in a state the broadcast can't reach.
Otherwise prefer the broadcast — it doesn't force you to redo the
accessibility/mic grant dance before every run.

## 2. Re-enable the accessibility service

Settings → Accessibility → Installed apps → Calo → On. Confirm the toggle
actually stuck (Android sometimes silently declines if battery optimization
or a "restricted setting" prompt intervened — reopen the screen once to
double check).

## 3. Check the mic permission

Trigger any voice command once and confirm no permission dialog appears
mid-flow. If it does, grant it now, not during the recorded take.

## 4. Groq key live check

Confirm `GROQ_API_KEY` is set for this build (`local.properties` or env var —
see README) and do one throwaway utterance against a already-taught flow to
confirm you get a real match back, not a silent "didn't recognize" caused by
a missing/expired key. Check logcat for `NLUClient` — an `HTTP 401` or
`GROQ_API_KEY is not set` means fix this now, not on stage.

```sh
adb -s acd41a26 logcat -s NLUClient
```

## 5. Phone on Do Not Disturb

An incoming call or notification banner mid-replay can steal focus from the
target app or cover the element Calo is about to tap — NodeWalker would then
correctly fail to resolve and the flow reports Stuck, but that's a wasted
take, not a real bug. Enable DND before every recorded run.

## 6. Target apps signed in with saved addresses (T6)

- **Zomato**: logged in, Home and Work addresses saved, a payment method on
  file up to (not including) actually completing a real payment — the
  credential gate is expected to halt at that screen (Finding: gate should
  fire when a payment field appears).
- **Amazon**: logged in, Home and Work addresses saved, same payment caveat.

Confirm both apps open straight to their logged-in home screen with no
"continue as guest" or re-auth interstitial — those are launch-screen states
`CredentialGate` may false-trigger on (see
[known-limitations.md](known-limitations.md), Finding 1).

## 7. Battery and screen timeout

Set screen timeout to the longest available option (or disable it) and
charge/plug in the phone. A screen lock mid-replay looks identical to "app
became unresponsive" from Calo's side and reports Stuck.

## Order matters

Do step 1 (wipe) **before** steps 2–7, since `pm clear` (if you end up needing
it) undoes the accessibility/mic grants from step 2/3 and would make you redo
them. The debug broadcast path doesn't have this problem — it's safe to run
at any point in this list.
