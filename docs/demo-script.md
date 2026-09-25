# Demo video script

Unedited, single continuous take, in order. Total budget: **5:00**, planned
run **≈3:50**, leaving **≥60s spare** for anything running slightly long
(a slow app cold-start, a re-read line) without blowing the 5:00 cap.

**No cuts rule:** if any segment fails mid-take, do not cut and splice —
restart the entire recording from 0:00. A judge who sees an edit point during
a "live teach" demo has reason to doubt every other segment. Run the
[pre-demo-checklist](pre-demo-checklist.md) fully before every take attempt,
not just the first.

## Shot list

| # | Segment | Time budget | Window |
|---|---------|-------------|--------|
| — | Intro | 0:15 | 0:00–0:15 |
| a | Teach: Zomato Margherita flow | 0:60 | 0:15–1:15 |
| b | Exact-utterance replay, halts at payment (gate) | 0:45 | 1:15–2:00 |
| c | Paraphrase replay | 0:35 | 2:00–2:35 |
| d | Changed-slot replay (Farmhouse) | 0:35 | 2:35–3:10 |
| e | Forced stuck, Calo asks a specific question | 0:40 | 3:10–3:50 |
| — | Outro | 0:10 | 3:50–4:00 |
| — | **Spare** | **1:00** | 4:00–5:00 |

## Intro (0:00–0:15)

**Spoken:** "This is Calo — teach it a flow once, by talking and tapping,
and it replays it later from a voice command. Everything you're about to
see is one continuous take, no cuts."

## (a) Teach the Zomato Margherita flow — 0:15–1:15

**Setup:** Zomato open, logged in, home screen. Accessibility service
confirmed on (checklist step 2).

**Spoken (before starting):** "I'm going to teach Calo how to order a
Margherita pizza from my usual place on Zomato."

**Action:** Trigger teach-start (debug broadcast or in-app control, per
whatever lane A's UI exposes by demo day). Narrate each tap briefly as you
perform it: open the restaurant, add Margherita to cart, proceed to (but do
not complete) checkout. Trigger teach-finish, speak the trigger utterance
out loud as you set it: **"Order my usual Margherita from \[restaurant\]."**

**Spoken (closing this segment):** "That's saved now — Calo has the steps,
anchored to the actual buttons, not screen coordinates."

**Fallback:** if a tap doesn't register or the wrong screen loads, restart
the full take from 0:00 — do not attempt to teach the flow twice in one
recording.

## (b) Exact-utterance replay, halts at payment — 1:15–2:00

**Setup:** Back to Zomato's home screen (or launch from Calo directly).

**Spoken:** "Now I'll say the exact phrase I taught it, word for word."

**Action:** Speak the trigger utterance from (a) verbatim. Let replay run
uninterrupted through cart/checkout navigation. When the payment screen
appears, do not tap anything — let Calo's credential gate halt it. Point at
the screen when it stops.

**Spoken (as it halts):** "And it stops — right here, at the payment screen.
Calo will never enter a card number or complete a purchase on its own; this
gate checks before every single step, not just once."

**Fallback:** if replay doesn't halt at the expected step (over- or
under-shoots), restart from 0:00. Do not manually stop the app to fake the
halt.

## (c) Paraphrase replay — 2:00–2:35

**Setup:** Return to Zomato's home screen.

**Spoken:** "This time I won't use the exact phrase — I'll say it a
different way and see if Calo still recognizes it."

**Action:** Speak a natural paraphrase of the original trigger (not scripted
word-for-word in advance beyond the general idea — this should sound like a
real paraphrase, e.g. "Can you get me the same pizza order as before"). Let
it match and start replaying; you can cut the segment short once the first
1–2 steps visibly execute correctly (no need to run all the way to the gate
again).

**Spoken (as it starts executing):** "Same flow, different words — the
matching is doing the work here, not a hardcoded phrase."

**Fallback:** if it fails to match, restart from 0:00. Do not repeat the
utterance in place — a judge who sees a retry inside the "single take" demo
will read it as a paraphrase that didn't actually work the first time.

## (d) Changed-slot replay (Farmhouse) — 2:35–3:10

**Setup:** Return to Zomato's home screen.

**Spoken:** "Now the same flow, but I'll change one thing — a different
pizza."

**Action:** Speak an utterance that keeps the same overall flow but swaps
the item slot, e.g. **"Order a Farmhouse instead."** Let it execute far
enough to visibly show Farmhouse (not Margherita) added to cart.

**Spoken (once Farmhouse is visibly in the cart):** "That's the slot
model — Calo isn't replaying a fixed recording, it's substituting into it."

**Fallback:** if the wrong item gets added or nothing changes, restart from
0:00.

## (e) Forced stuck, Calo asks a specific question — 3:10–3:50

**Setup:** Use whatever reliably reproduces a Stuck state for this build —
e.g. speak a command for a flow whose target screen has since changed layout
(button moved/renamed since teaching), or intentionally navigate the target
app to an unexpected screen before speaking the command.

**Spoken (before triggering):** "And finally — what happens when Calo can't
find what it's looking for. I'm going to give it a command it can't
complete."

**Action:** Trigger the command that leads to Stuck. Let the UI surface the
question/reason to the screen; read it aloud as it appears.

**Spoken (as it surfaces):** "It doesn't guess and it doesn't tap something
random — it stops and tells me exactly what it couldn't find."

**Fallback:** if it fails to reach Stuck (e.g. it Completes or Halts
instead), restart from 0:00. If Stuck is reached but the on-screen reason is
not phrased as a specific, readable question yet (see
[architecture.md](architecture.md)'s Stuck-design note — this is UI work in
progress as of this doc), read the raw reason string aloud instead of
skipping the beat.

## Outro (3:50–4:00)

**Spoken:** "Teach once, replay by voice, and it knows when to stop. Thanks
for watching."

## Notes for whoever records this

- Rehearse the exact utterances for (a)/(c)/(d) beforehand so the paraphrase
  in (c) sounds natural rather than hesitant, but do not read them off a
  script on camera.
- Keep the phone's screen recording and the presenter's voice on the same
  take (single source), not composited afterward — "unedited" means literally
  one recording, start to finish.
- If a take runs past 5:00 for any reason, it's a failed take — restart, do
  not trim in post.
