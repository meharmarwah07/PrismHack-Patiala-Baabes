# Pre-demo checklist

Run this in order, right before any judging or recording session. Every step
exists because it bit us once during rehearsal — this is not boilerplate.

## 1. Wipe the flow library

Every taught flow from every rehearsal is sitting in the Room DB. If you skip
this, the NLU match step sees stale/duplicate candidates (multiple "order a
Margherita" flows from different takes) and confidence gets noisy.

**On judging day (release build): `adb shell pm clear com.calo`** — the app
will be submitted and judged as a release APK, and `DEBUG_RESET` (below) is
compiled out of release builds entirely, so it will silently no-op if you
reach for it there. `pm clear` is the method that actually works against
what gets judged:

```sh
adb -s acd41a26 shell pm clear com.calo
```

This wipes the Room DB, but it also wipes *everything else app-scoped*:

- The accessibility-service grant (Settings → Accessibility → Calo flips back
  to off — replay and teach both silently do nothing until it's re-enabled).
- The RECORD_AUDIO runtime permission (next mic use re-prompts).
- Any other granted runtime permissions.
- BuildConfig.GROQ_API_KEY is unaffected (baked in at build time, not app
  data), but if you also reinstall the APK you obviously start fresh there
  too.

Immediately after running `pm clear`, do steps 2 and 3 below (re-enable the
accessibility service, re-grant the mic permission) — on judging day these
are not optional contingencies, they're required every single time you wipe.

**Dev-testing only (debug build): `DEBUG_RESET` broadcast** — clears rows
without touching the accessibility grant or mic permission, so it's faster
to iterate with tonight/tomorrow while rehearsing. Not usable on judging day.

```sh
adb -s acd41a26 shell am broadcast -a com.calo.DEBUG_RESET -p com.calo
```

Confirm it worked:

```sh
adb -s acd41a26 logcat -s CaloDebugReset
```

You should see a line like `learned_flows reset: 4 row(s) -> 0 row(s)`. This
receiver (`DebugResetReceiver`) only exists in debug builds — see
[architecture.md](architecture.md) — so this command is a silent no-op
against the release build you'll actually be judged on.

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

Also clear Zomato's recent-search / search-history state before the final
teach and before judging. A taught flow that relies on tapping a recent
search entry (instead of typing the query fresh) will not replay on a fresh
account or after a DB wipe, because that shortcut won't exist there.

## 7. Battery and screen timeout

Set screen timeout to the longest available option (or disable it) and
charge/plug in the phone. A screen lock mid-replay looks identical to "app
became unresponsive" from Calo's side and reports Stuck.

## 8. Teaching technique for live judging

Traced tonight's T1 Zomato failures (wrong item added, duplicate consecutive
steps) to fast/imprecise teaching, not a code bug. Apply this every time a
flow is taught, including on judging day:

- Teach with single, deliberate taps — one action, brief pause, confirm the
  screen shows what you expect, then proceed. Do not tap rapidly or tap the
  same element more than once "to be sure."
- If a screen is slow to respond, wait for it rather than re-tapping.
- After teaching, always dump/review the saved flow's steps before relying on
  it — check for duplicate consecutive steps or an unexpected item/anchor,
  and re-teach immediately if something looks wrong rather than proceeding to
  replay testing on a bad flow.
- Replay the flow once immediately after teaching it, before moving to the
  next flow or the next slot type. A step list that looks correct can still
  fail at replay (timing, stale node references) — catch that per-flow, not
  in a stacked final test pass.

## 9. Release-build smoke test (before freeze — one-time, not day-of)

Build the actual release APK (`./gradlew assembleRelease`), install it fresh
on the demo device, and confirm Groq/NLU calls still work end-to-end. Nothing
so far has verified the release build works — release and debug builds can
differ in how config like `GROQ_API_KEY` gets injected, and this hasn't been
checked. Do this with enough runway before 29 Sep to fix it if it doesn't.

## Order matters

Do step 1 (wipe) **before** steps 2–7. On judging day, wiping via `pm clear`
undoes the accessibility/mic grants from steps 2/3, so those two steps are
mandatory after every wipe, not optional — budget time for them. (The
debug-only `DEBUG_RESET` broadcast doesn't have this problem, but it isn't
available on the release build you'll actually be judged on.) Step 9 is a
one-time pre-freeze task, not part of the day-of sequence.
