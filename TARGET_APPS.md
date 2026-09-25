# Target apps — Zomato and Amazon

These are not arbitrary picks for demo polish — they are the literal apps
named in the judges' test scripts:

- **T1–T7** test ordering a Margherita pizza "from Domino's on Zomato" —
  i.e. the flow is taught and replayed inside the Zomato app, ordering from
  a Domino's storefront within it.
- **T8–T9** test Amazon search + cart — a flow taught and replayed inside
  the Amazon app.

Calo's architecture already assumes multi-app operation by design, not as
something bolted on for these two: `LearnedFlow.targetPackage`
(`app/.../data/LearnedFlow.kt`) is stored per flow, `CaloOrchestrator`
matches an utterance against flows across *every* taught app in one NLU
call (`NluPrompt.build()`, see `ARCHITECTURE.md` §1), and
`launchAndWaitForForeground()` brings the correct app to the foreground
before replaying if it isn't already open. Nothing in the codebase hardcodes
Zomato or Amazon specifically — `ElementAnchor`'s own doc comment uses
`com.dominos.app:id/btn_add_to_cart` only as an illustrative example, not a
real anchor recorded against either app. Every anchor Calo actually uses is
produced by teaching a real flow against the real app (see
`ARCHITECTURE.md` §2) — nothing here is pre-taught or seeded.

## Test account requirement — T6

T6 requires a Zomato test account with **"Home" and "Work" saved
addresses** already configured, so a taught flow that depends on selecting
a saved address (rather than typing one fresh each time) has something
real to select during replay.

**This has not been confirmed set up.** I checked the repository (README,
git log, and every file in this repo) for any record of a Zomato account,
credentials, or saved-address configuration and found none — no seed data,
no setup script, no note in README or in any commit message. If no one has
separately confirmed this against the actual test device/emulator that
will run the demo, it needs to be done before T6 is attempted: sign into
Zomato with the account that will be used for judging, and add both a
"Home" and a "Work" address under Saved Addresses before teaching or
replaying anything that depends on them.

## What this means for teaching flows against these apps

Per `KNOWN_LIMITATIONS.md`, two things specific to Zomato/Amazon are worth
planning around before the demo, not during it:

- **Cross-app launch lands on each app's default screen, not a specific
  one.** If a flow is taught starting partway into Zomato (e.g. already
  inside the Domino's storefront) rather than from Zomato's own home
  screen, voice-triggered replay will open Zomato fresh and may not be able
  to resolve the first taught anchor. Teach flows starting from the app's
  actual launch state where possible, or account for the extra navigation
  steps needed to get there when teaching.
- **The credential gate will legitimately block replay on payment/login
  screens in both apps** — that's by design (see `ARCHITECTURE.md` §4 and
  the T11 case), not a bug to route around. If a test script's flow walks
  through a real payment step in Zomato or Amazon, expect and plan for a
  `Halted` result at that point rather than a `Completed` one — Calo is not
  meant to complete a purchase past that screen.
