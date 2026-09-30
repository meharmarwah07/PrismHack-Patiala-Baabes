# Target apps — Zomato, Spotify, Skribbl

Calo is app-agnostic by construction: nothing in the codebase hardcodes a
package name, a screen, or an anchor. Every anchor it uses is produced by
teaching a real flow against a real app at runtime (see `ARCHITECTURE.md`
§2) — nothing is pre-taught or seeded. `LearnedFlow.targetPackage`
(`app/.../data/LearnedFlow.kt`) is stored per flow, `CaloOrchestrator`
matches an utterance against flows across *every* taught app in a single
NLU call (`NluPrompt.build()`, `ARCHITECTURE.md` §1), and
`launchAndWaitForForeground()` brings the right app to the foreground
before replaying.

The three apps below are the ones we have taught and replayed on a real
device and are prepared to demonstrate.

## Zomato (`com.application.zomato`)

The food-ordering target named in the judges' test scripts (T1–T7): a
Margherita pizza from a Domino's storefront inside Zomato.

Zomato is also the hardest of the three, and the reason is documented in
detail in `KNOWN_LIMITATIONS.md` (30 Sep section): its search-results
screen is Jetpack Compose, which exposes a thin accessibility surface.
Result cards accept `ACTION_CLICK` and ignore it, click events are
dispatched on the window root, and two different text fields share one
resource id. Replay handles all three — Compose leaves are driven with a
real touch gesture at the matched label's own bounds rather than
`ACTION_CLICK`, window-root anchors are refused, and the resourceId lookup
consults `indexInParent` — but teaching on Zomato remains pacing-sensitive
(roughly two seconds between taps, no scrolling in between). See
`docs/pre-demo-checklist.md`.

### Test account requirement — T6

T6 requires a Zomato account with **"Home" and "Work" saved addresses**
already configured, so a flow that selects a saved address has something
real to select at replay time. Confirm this on the judging device before
T6 is attempted: sign in, then add both addresses under Saved Addresses
before teaching anything that depends on them.

## Spotify (`com.spotify.music`)

A second, structurally different target: navigation and list selection
rather than search-and-checkout. Taught flows here anchor cleanly on
readable labels ("Your Library", a playlist name, "Play playlist", a track
name), which makes it a good demonstration that the mechanism is not
tuned to one app's DOM.

## Skribbl

A browser-based drawing game, taught and replayed through its web UI. It
exercises the parts of the system that food and music apps don't: rapid
screen changes, and a UI that is not a conventional Android view
hierarchy.

## Not supported: Swiggy

We evaluated Swiggy as an alternative food-ordering target and are not
declaring it. Its results screen exposes **zero** actionable elements to
the accessibility tree, and a teaching session there recorded no
`OPEN_SEARCH`, `SEARCH_INPUT` or `SUBMIT_SEARCH` step at all — the search
interaction is simply not visible to an accessibility service. Evidence is
in `KNOWN_LIMITATIONS.md`.

## A note on T8–T9 (Amazon)

T8 and T9 are scored against Amazon. We are not declaring Amazon as a
prepared target, for two reasons — one incidental, one structural.

**Incidental:** our teaching runs against Amazon produced unusable anchors
(a `SELECT_RESULT` step anchored to `android:id/navigationBarBackground`,
i.e. the system navigation bar). That is a capture-quality problem of the
same family as the Compose issues in `KNOWN_LIMITATIONS.md`.

**Structural, and the more important one:** Amazon's **home screen carries
an "Amazon Pay" entry point**. `CredentialGateRules` is evaluated before
*every* replay step, and payment wording on screen is what it exists to
stop on. Amazon's home screen therefore trips the gate at step 1 and
replay halts before the flow begins.

This is the gate working as designed, not a bug — but it is a genuine
cost, and we would rather name it than hide it. The gate is deliberately
conservative because T11 is worth 5 points with a **−10** penalty for
failing it: a gate that lets one payment screen through is far more
expensive than a gate that refuses an app whose home screen advertises a
wallet. We accepted that trade knowingly.

The narrower fix — distinguishing a payment *entry point* (a link or tab
that navigates towards payment) from a payment *entry screen* (one that
collects card, UPI or OTP details) — is the right long-term design and is
sketched in `KNOWN_LIMITATIONS.md`. It was not something we were willing
to change on submission day, because getting it wrong costs 15 points of
swing on T11.
