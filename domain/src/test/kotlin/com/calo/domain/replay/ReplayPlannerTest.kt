package com.calo.domain.replay

import com.calo.domain.gate.ScreenSignals
import com.calo.domain.model.ActionType
import com.calo.domain.model.ElementAnchor
import com.calo.domain.model.FlowStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Ignore
import org.junit.Test

/** In-memory fake standing in for the real AccessibilityNodeInfo-backed adapter. */
private class FakeNode(val id: String) : NodeHandle

private class FakeNodeProvider(
    // 1-indexed by CALL number, not step.order: ReplayPlanner calls
    // currentScreenSignals() exactly once per step, in order, so the Nth
    // call corresponds to "the screen right before step N's gate check."
    // This mirrors the real provider (which has no notion of "step index"
    // either — it just always reads whatever is live on screen right now).
    private val screenSignalsByCall: Map<Int, ScreenSignals> = emptyMap(),
    private val defaultSignals: ScreenSignals,
    private val missingAnchors: Set<String> = emptySet(),
    private val failingActions: Set<String> = emptySet(), // node ids that fail their action
    // Every label currently on screen, ONE ENTRY PER ELEMENT (duplicates
    // allowed on purpose — two different rows can share a label, which is
    // exactly the "two candidates" ambiguity case). findNodeByValue below
    // routes through the REAL ClickValueMatcher, the same algorithm
    // NodeWalker.findBySlotValue uses in :app, so this fake proves the
    // production matching/ambiguity rules, not a hand-simplified stand-in.
    private val availableOptions: List<String> = emptyList(),
    // Simulates the screen changing BETWEEN two replay steps that share the
    // same taught anchor (2026-09-26: tonight's Zomato teach run recorded
    // the same "Domino's Pizza" CLICK anchor 5 times in a row) -- keyed by
    // anchor id, then by WHICH call number for that id (1st, 2nd, ...)
    // should resolve to null instead of a real node. A static
    // `missingAnchors` entry can't express "found the first time, gone by
    // the second", since it applies to every call for that id.
    private val missingOnOccurrence: Map<String, Set<Int>> = emptyMap(),
    // Same idea, but "the anchor still resolves to SOMETHING on the new
    // screen, just not the same element" (e.g. a generic resourceId reused
    // across screens) -- returns a distinguishable stand-in node so a test
    // can prove a duplicate step never taps it.
    private val differentNodeOnOccurrence: Map<String, Set<Int>> = emptyMap(),
    // SUBMIT_SEARCH / ACTION_IME_ENTER fakes.
    private val imeEnterApiSupported: Boolean = false,
    private val imeUnsupportedNodes: Set<String> = emptySet(), // ids that resolve but don't expose ACTION_IME_ENTER
    private val imeFailingNodes: Set<String> = emptySet() // ids where performImeEnter() itself returns false
) : NodeProvider {
    private var callCount = 0
    val clickCalls = mutableListOf<String>()
    val setTextCalls = mutableListOf<Pair<String, String>>()
    val scrollCalls = mutableListOf<String>()
    val findNodeCalls = mutableListOf<String>()
    val imeEnterCalls = mutableListOf<String>()
    var idleCalls = 0
    var imeEnterApiSupportedCallCount = 0
    private val findNodeOccurrences = mutableMapOf<String, Int>()

    override fun currentScreenSignals(): ScreenSignals {
        callCount++
        return screenSignalsByCall[callCount] ?: defaultSignals
    }

    override fun findNode(anchor: ElementAnchor): NodeHandle? {
        val id = anchor.resourceId ?: anchor.text ?: "unknown"
        findNodeCalls += id
        val occurrence = (findNodeOccurrences[id] ?: 0) + 1
        findNodeOccurrences[id] = occurrence

        if (id in missingAnchors) return null
        if (occurrence in (missingOnOccurrence[id] ?: emptySet())) return null
        if (occurrence in (differentNodeOnOccurrence[id] ?: emptySet())) return FakeNode("$id#occurrence$occurrence")
        return FakeNode(id)
    }

    override fun findNodeByValue(value: String): NodeHandle? {
        val chosen = ClickValueMatcher.resolve(availableOptions, value) { it } ?: return null
        return FakeNode("option:$chosen")
    }

    override fun performClick(node: NodeHandle): Boolean {
        val id = (node as FakeNode).id
        clickCalls += id
        return id !in failingActions
    }

    override fun performSetText(node: NodeHandle, value: String): Boolean {
        val id = (node as FakeNode).id
        setTextCalls += id to value
        return id !in failingActions
    }

    override fun performScroll(node: NodeHandle, forward: Boolean): Boolean {
        val id = (node as FakeNode).id
        scrollCalls += id
        return id !in failingActions
    }

    override fun awaitIdle() {
        idleCalls++
    }

    override fun imeEnterApiSupported(): Boolean {
        imeEnterApiSupportedCallCount++
        return imeEnterApiSupported
    }

    override fun nodeSupportsImeEnter(node: NodeHandle): Boolean {
        val id = (node as FakeNode).id
        return id !in imeUnsupportedNodes
    }

    override fun performImeEnter(node: NodeHandle): Boolean {
        val id = (node as FakeNode).id
        imeEnterCalls += id
        return id !in imeFailingNodes
    }
}

class ReplayPlannerTest {

    private val clearSignals = ScreenSignals(packageName = "com.dominos.app", allText = listOf("Menu"))
    private val paymentSignals = ScreenSignals(packageName = "com.dominos.app", allText = listOf("Enter your CVV"))

    private fun clickStep(order: Int, resourceId: String) = FlowStep(
        order = order, action = ActionType.CLICK, target = ElementAnchor(resourceId = resourceId)
    )

    private fun setTextStep(order: Int, resourceId: String, recorded: String, slotName: String? = null) = FlowStep(
        order = order, action = ActionType.SET_TEXT, target = ElementAnchor(resourceId = resourceId),
        recordedValue = recorded, slotName = slotName
    )

    private fun clickStepWithSlot(order: Int, resourceId: String, recordedText: String, slotName: String) = FlowStep(
        order = order, action = ActionType.CLICK,
        target = ElementAnchor(resourceId = resourceId, text = recordedText), slotName = slotName
    )

    @Test
    fun `completes a fully clear linear flow`() {
        val steps = listOf(
            clickStep(1, "id/menu"),
            setTextStep(2, "id/search", "Margherita"),
            clickStep(3, "id/add_to_cart")
        )
        val provider = FakeNodeProvider(emptyMap(), clearSignals)
        val result = ReplayPlanner.replay(steps, emptyMap(), provider)

        assertEquals(ReplayResult.Completed, result)
        assertEquals(listOf("id/menu", "id/add_to_cart"), provider.clickCalls)
        assertEquals(listOf("id/search" to "Margherita"), provider.setTextCalls)
    }

    @Test
    fun `T11 -- halts the instant a payment screen appears, zero taps attempted from that step on`() {
        val steps = listOf(
            clickStep(1, "id/menu"),
            clickStep(2, "id/checkout"),
            clickStep(3, "id/add_card"),          // <- payment screen appears before this step runs
            setTextStep(4, "id/card_number", "4242424242424242"),
            clickStep(5, "id/confirm_pay")
        )
        // Screen is clear through step 2; by the time step 3's gate-check runs, it's a payment screen.
        val signalsByCall = mapOf(1 to clearSignals, 2 to clearSignals, 3 to paymentSignals)
        val provider = FakeNodeProvider(signalsByCall, defaultSignals = paymentSignals)

        val result = ReplayPlanner.replay(steps, emptyMap(), provider)

        assertTrue(result is ReplayResult.Halted)
        assertEquals(3, (result as ReplayResult.Halted).atStepOrder)
        // The two steps before the payment screen legitimately ran...
        assertEquals(listOf("id/menu", "id/checkout"), provider.clickCalls)
        // ...but nothing from step 3 onward was ever attempted: no card number
        // was typed, no confirm-pay tap happened. This is the exact guarantee
        // the compaction summary flagged as never having been runtime-verified.
        assertTrue(provider.setTextCalls.isEmpty())
        assertTrue(provider.clickCalls.none { it == "id/add_card" || it == "id/confirm_pay" })
    }

    @Test
    fun `halts immediately if the very first step is already a login screen`() {
        val steps = listOf(clickStep(1, "id/username_field"), clickStep(2, "id/login_button"))
        val provider = FakeNodeProvider(emptyMap(), defaultSignals = ScreenSignals(
            packageName = "com.example.app", allText = listOf("Sign in to continue")
        ))
        val result = ReplayPlanner.replay(steps, emptyMap(), provider)
        assertEquals(ReplayResult.Halted(1, "login-related content detected: \"sign in\""), result)
        assertTrue(provider.clickCalls.isEmpty())
    }

    @Test
    fun `element not found is Stuck, not silently skipped`() {
        val steps = listOf(clickStep(1, "id/renamed_button"))
        val provider = FakeNodeProvider(emptyMap(), clearSignals, missingAnchors = setOf("id/renamed_button"))
        val result = ReplayPlanner.replay(steps, emptyMap(), provider)
        assertTrue(result is ReplayResult.Stuck)
        assertEquals(1, (result as ReplayResult.Stuck).atStepOrder)
    }

    @Test
    fun `ACTION_SET_TEXT returning false (custom widget) is Stuck, not treated as success`() {
        val steps = listOf(setTextStep(1, "id/weird_custom_field", "hello"))
        val provider = FakeNodeProvider(emptyMap(), clearSignals, failingActions = setOf("id/weird_custom_field"))
        val result = ReplayPlanner.replay(steps, emptyMap(), provider)
        assertTrue(result is ReplayResult.Stuck)
        assertEquals(1, provider.setTextCalls.size) // it was attempted...
        assertTrue((result as ReplayResult.Stuck).reason.contains("SET_TEXT"))
    }

    @Test
    fun `slot substitution overrides the recorded literal mid-flow (T4-T6 generalization)`() {
        val steps = listOf(setTextStep(1, "id/item_search", "Margherita", slotName = "item"))
        val provider = FakeNodeProvider(emptyMap(), clearSignals)
        val result = ReplayPlanner.replay(steps, mapOf("item" to "Pepperoni"), provider)
        assertEquals(ReplayResult.Completed, result)
        assertEquals(listOf("id/item_search" to "Pepperoni"), provider.setTextCalls)
    }

    @Test
    fun `CLICK slot value differing from recorded searches by the new value, not the stale original anchor`() {
        // Taught tapping "Home" (resourceId id/address_row); replaying with
        // slot value "Work" available on screen must tap "Work", never fall
        // back to re-resolving id/address_row (which would silently tap
        // whatever "Home" still resolves to — the exact bug being fixed).
        val steps = listOf(clickStepWithSlot(1, "id/address_row", recordedText = "Home", slotName = "address"))
        val provider = FakeNodeProvider(emptyMap(), clearSignals, availableOptions = listOf("Work"))
        val result = ReplayPlanner.replay(steps, mapOf("address" to "Work"), provider)

        assertEquals(ReplayResult.Completed, result)
        assertEquals(listOf("option:Work"), provider.clickCalls)
        assertTrue(provider.clickCalls.none { it == "id/address_row" })
    }

    @Test
    fun `CLICK slot value with no matching option on screen is Stuck, never falls back to the wrong original tap`() {
        val steps = listOf(clickStepWithSlot(1, "id/address_row", recordedText = "Home", slotName = "address"))
        val provider = FakeNodeProvider(emptyMap(), clearSignals, availableOptions = emptyList())
        val result = ReplayPlanner.replay(steps, mapOf("address" to "Work"), provider)

        assertTrue(result is ReplayResult.Stuck)
        assertEquals(1, (result as ReplayResult.Stuck).atStepOrder)
        assertTrue(result.reason.contains("Work"))
        assertTrue(provider.clickCalls.isEmpty()) // no wrong tap attempted either
    }

    @Test
    fun `CLICK item substitution -- two candidates on screen is Stuck, never guesses`() {
        // Two distinct result rows both happen to be labeled "Farmhouse"
        // (e.g. regular and stuffed-crust variants) — must never guess.
        val steps = listOf(clickStepWithSlot(1, "id/item_search", recordedText = "Margherita", slotName = "item"))
        val provider = FakeNodeProvider(emptyMap(), clearSignals, availableOptions = listOf("Farmhouse", "Farmhouse"))
        val result = ReplayPlanner.replay(steps, mapOf("item" to "Farmhouse"), provider)

        assertTrue(result is ReplayResult.Stuck)
        assertEquals(1, (result as ReplayResult.Stuck).atStepOrder)
        assertTrue(provider.clickCalls.isEmpty())
    }

    @Test
    fun `CLICK item substitution -- case-insensitive, trimmed exact match`() {
        val steps = listOf(clickStepWithSlot(1, "id/item_search", recordedText = "Margherita", slotName = "item"))
        val provider = FakeNodeProvider(emptyMap(), clearSignals, availableOptions = listOf(" FARMHOUSE "))
        val result = ReplayPlanner.replay(steps, mapOf("item" to "farmhouse"), provider)

        assertEquals(ReplayResult.Completed, result)
        assertEquals(listOf("option: FARMHOUSE "), provider.clickCalls)
    }

    @Test
    fun `CLICK item substitution -- unique contains-match used only when exact match fails`() {
        val steps = listOf(clickStepWithSlot(1, "id/item_search", recordedText = "Margherita", slotName = "item"))
        val provider = FakeNodeProvider(emptyMap(), clearSignals, availableOptions = listOf("Farmhouse Deluxe"))
        val result = ReplayPlanner.replay(steps, mapOf("item" to "Farmhouse"), provider)

        assertEquals(ReplayResult.Completed, result)
        assertEquals(listOf("option:Farmhouse Deluxe"), provider.clickCalls)
    }

    @Test
    fun `CLICK item substitution -- ambiguous contains-match (two candidates) is Stuck`() {
        val steps = listOf(clickStepWithSlot(1, "id/item_search", recordedText = "Margherita", slotName = "item"))
        val provider = FakeNodeProvider(
            emptyMap(), clearSignals,
            availableOptions = listOf("Farmhouse Deluxe", "Farmhouse Feast")
        )
        val result = ReplayPlanner.replay(steps, mapOf("item" to "Farmhouse"), provider)

        assertTrue(result is ReplayResult.Stuck)
        assertEquals(1, (result as ReplayResult.Stuck).atStepOrder)
        assertTrue(provider.clickCalls.isEmpty())
    }

    @Test
    fun `CLICK slot value equal to what was recorded uses the normal anchor path, not the search path`() {
        val steps = listOf(clickStepWithSlot(1, "id/address_row", recordedText = "Home", slotName = "address"))
        val provider = FakeNodeProvider(emptyMap(), clearSignals) // no availableOptions configured
        val result = ReplayPlanner.replay(steps, mapOf("address" to "Home"), provider)

        assertEquals(ReplayResult.Completed, result)
        assertEquals(listOf("id/address_row"), provider.clickCalls)
    }

    @Test
    fun `CLICK slotName present but no value supplied (T2, exact replay) uses the normal anchor path unchanged`() {
        val steps = listOf(clickStepWithSlot(1, "id/address_row", recordedText = "Home", slotName = "address"))
        val provider = FakeNodeProvider(emptyMap(), clearSignals)
        val result = ReplayPlanner.replay(steps, emptyMap(), provider)

        assertEquals(ReplayResult.Completed, result)
        assertEquals(listOf("id/address_row"), provider.clickCalls)
    }

    @Test
    fun `SET_TEXT slot substitution is unaffected by the CLICK slot-search fix`() {
        // Same flow as the pre-existing generalization test, run again after
        // touching ReplayPlanner's CLICK branch, to pin down that SET_TEXT's
        // code path (SlotResolver.resolveValue, provider.findNode) was not
        // disturbed by adding the CLICK-only resolveClickTarget path.
        val steps = listOf(
            setTextStep(1, "id/item_search", "Margherita", slotName = "item"),
            clickStepWithSlot(2, "id/address_row", recordedText = "Home", slotName = "address")
        )
        val provider = FakeNodeProvider(emptyMap(), clearSignals, availableOptions = listOf("Work"))
        val result = ReplayPlanner.replay(steps, mapOf("item" to "Pepperoni", "address" to "Work"), provider)

        assertEquals(ReplayResult.Completed, result)
        assertEquals(listOf("id/item_search" to "Pepperoni"), provider.setTextCalls)
        assertEquals(listOf("option:Work"), provider.clickCalls)
    }

    @Test
    fun `WAIT never fails and advances without touching any node`() {
        val steps = listOf(FlowStep(order = 1, action = ActionType.WAIT, target = ElementAnchor()))
        val provider = FakeNodeProvider(emptyMap(), clearSignals)
        val result = ReplayPlanner.replay(steps, emptyMap(), provider)
        assertEquals(ReplayResult.Completed, result)
        assertEquals(1, provider.idleCalls)
    }

    // ---- consecutive duplicate CLICK steps (2026-09-26 Zomato capture bug) ----

    @Ignore("Replay-side duplicate-CLICK guard removed 2026-09-30 — see ReplayPlanner's REMOVED note. Duplicate capture is prevented at teach time (TapDedup/TouchClaim). Rewrite or delete these post-submission.")
    @Test
    fun `consecutive identical CLICK steps -- only the first is tapped, duplicates never re-resolved`() {
        // Mirrors tonight's actual Zomato capture: the same anchor recorded
        // 5 times in a row. Proves the fix at the strongest level: not just
        // "no extra taps", but that findNode is never even CALLED again for
        // the duplicates -- the old code would have called it 5 times.
        val steps = listOf(
            clickStep(1, "id/pizza_row"),
            clickStep(2, "id/pizza_row"),
            clickStep(3, "id/pizza_row"),
            clickStep(4, "id/pizza_row"),
            clickStep(5, "id/pizza_row")
        )
        val provider = FakeNodeProvider(emptyMap(), clearSignals)
        val result = ReplayPlanner.replay(steps, emptyMap(), provider)

        assertEquals(ReplayResult.Completed, result)
        assertEquals(listOf("id/pizza_row"), provider.clickCalls)
        assertEquals(listOf("id/pizza_row"), provider.findNodeCalls)
    }

    @Test
    fun `consecutive identical CLICK steps still gate-check every step, even the skipped ones`() {
        // The "checked before EVERY step, no exceptions" guarantee must not
        // grow a silent gap for a step that gets skipped as a duplicate --
        // a payment screen appearing on step 2 must still halt there.
        val steps = listOf(
            clickStep(1, "id/pizza_row"),
            clickStep(2, "id/pizza_row")
        )
        val signalsByCall = mapOf(1 to clearSignals, 2 to paymentSignals)
        val provider = FakeNodeProvider(signalsByCall, defaultSignals = paymentSignals)
        val result = ReplayPlanner.replay(steps, emptyMap(), provider)

        assertTrue(result is ReplayResult.Halted)
        assertEquals(2, (result as ReplayResult.Halted).atStepOrder)
        assertEquals(listOf("id/pizza_row"), provider.clickCalls) // step 1 ran; step 2 never reached the dup-skip at all
    }

    @Ignore("Replay-side duplicate-CLICK guard removed 2026-09-30 — see ReplayPlanner's REMOVED note. Duplicate capture is prevented at teach time (TapDedup/TouchClaim). Rewrite or delete these post-submission.")
    @Test
    fun `a real screen transition does NOT suppress deduplication -- the risk this fix closes`() {
        // The scenario the "harmless no-op" assumption never actually
        // tested: by the time step 2 would run, the screen has moved on and
        // this same anchor id no longer resolves to anything at all. Under
        // the OLD behavior (blind re-resolve) this would go Stuck. Under the
        // fix, step 2 is skipped before ever calling findNode, so it never
        // has the chance to fail -- proving the duplicate is genuinely never
        // re-resolved, not just "re-resolved and got lucky".
        val steps = listOf(
            clickStep(1, "id/pizza_row"),
            clickStep(2, "id/pizza_row")
        )
        val provider = FakeNodeProvider(
            emptyMap(), clearSignals,
            missingOnOccurrence = mapOf("id/pizza_row" to setOf(2))
        )
        val result = ReplayPlanner.replay(steps, emptyMap(), provider)

        assertEquals(ReplayResult.Completed, result)
        assertEquals(listOf("id/pizza_row"), provider.clickCalls)
        assertEquals(listOf("id/pizza_row"), provider.findNodeCalls) // only 1 entry: step 2 never attempted
    }

    @Ignore("Replay-side duplicate-CLICK guard removed 2026-09-30 — see ReplayPlanner's REMOVED note. Duplicate capture is prevented at teach time (TapDedup/TouchClaim). Rewrite or delete these post-submission.")
    @Test
    fun `a same-id DIFFERENT element on the new screen is also never tapped by a duplicate step`() {
        // The subtler half of the same risk: the anchor DOES still resolve
        // on the new screen (same resourceId/text can legitimately describe
        // a different real element, e.g. a generic id reused across
        // screens) -- so the old "no-op" assumption wasn't even reliably
        // safe when the anchor DID resolve. This configures step 2's
        // occurrence to resolve to a distinguishable stand-in node; the fix
        // must never tap it, because step 2 is skipped before resolving at all.
        val steps = listOf(
            clickStep(1, "id/pizza_row"),
            clickStep(2, "id/pizza_row")
        )
        val provider = FakeNodeProvider(
            emptyMap(), clearSignals,
            differentNodeOnOccurrence = mapOf("id/pizza_row" to setOf(2))
        )
        val result = ReplayPlanner.replay(steps, emptyMap(), provider)

        assertEquals(ReplayResult.Completed, result)
        assertEquals(listOf("id/pizza_row"), provider.clickCalls)
        assertTrue(provider.clickCalls.none { it.contains("occurrence") })
    }

    @Ignore("Replay-side duplicate-CLICK guard removed 2026-09-30 — see ReplayPlanner's REMOVED note. Duplicate capture is prevented at teach time (TapDedup/TouchClaim). Rewrite or delete these post-submission.")
    @Test
    fun `duplicate CLICK detection requires the SAME anchor -- different anchors both run normally`() {
        val steps = listOf(
            clickStep(1, "id/pizza_row"),
            clickStep(2, "id/add_to_cart") // different anchor -- not a duplicate, must still run
        )
        val provider = FakeNodeProvider(emptyMap(), clearSignals)
        val result = ReplayPlanner.replay(steps, emptyMap(), provider)

        assertEquals(ReplayResult.Completed, result)
        assertEquals(listOf("id/pizza_row", "id/add_to_cart"), provider.clickCalls)
    }

    // ---- SUBMIT_SEARCH / ACTION_IME_ENTER (2026-09-28) ----

    private fun submitSearchStep(order: Int, resourceId: String) = FlowStep(
        order = order, action = ActionType.SUBMIT_SEARCH, target = ElementAnchor(resourceId = resourceId),
        recordedValue = "pizza"
    )

    @Test
    fun `SUBMIT_SEARCH on an unsupported API level is Stuck before any node is resolved`() {
        val steps = listOf(submitSearchStep(1, "id/search_field"))
        val provider = FakeNodeProvider(emptyMap(), clearSignals, imeEnterApiSupported = false)
        val result = ReplayPlanner.replay(steps, emptyMap(), provider)

        assertTrue(result is ReplayResult.Stuck)
        assertEquals(1, (result as ReplayResult.Stuck).atStepOrder)
        assertTrue(result.reason.contains("IME_ENTER_UNSUPPORTED"))
        assertTrue(provider.findNodeCalls.isEmpty()) // never even tried to resolve the target
        assertTrue(provider.imeEnterCalls.isEmpty())
    }

    @Test
    fun `SUBMIT_SEARCH element not found is Stuck, same discipline as CLICK-SET_TEXT-SCROLL`() {
        val steps = listOf(submitSearchStep(1, "id/renamed_search_field"))
        val provider = FakeNodeProvider(
            emptyMap(), clearSignals,
            imeEnterApiSupported = true,
            missingAnchors = setOf("id/renamed_search_field")
        )
        val result = ReplayPlanner.replay(steps, emptyMap(), provider)

        assertTrue(result is ReplayResult.Stuck)
        assertEquals(1, (result as ReplayResult.Stuck).atStepOrder)
        assertTrue(result.reason.contains("element not found"))
        assertTrue(provider.imeEnterCalls.isEmpty())
    }

    @Test
    fun `SUBMIT_SEARCH on a resolved node with no ACTION_IME_ENTER in its actionList is Stuck, not a coordinate-guess fallback`() {
        val steps = listOf(submitSearchStep(1, "id/search_field"))
        val provider = FakeNodeProvider(
            emptyMap(), clearSignals,
            imeEnterApiSupported = true,
            imeUnsupportedNodes = setOf("id/search_field")
        )
        val result = ReplayPlanner.replay(steps, emptyMap(), provider)

        assertTrue(result is ReplayResult.Stuck)
        assertEquals(1, (result as ReplayResult.Stuck).atStepOrder)
        assertTrue(result.reason.contains("IME_ENTER_UNSUPPORTED"))
        assertTrue(provider.imeEnterCalls.isEmpty()) // never invoked on a node that doesn't support it
    }

    @Test
    fun `SUBMIT_SEARCH invokes ACTION_IME_ENTER and completes when everything checks out`() {
        val steps = listOf(submitSearchStep(1, "id/search_field"))
        val provider = FakeNodeProvider(emptyMap(), clearSignals, imeEnterApiSupported = true)
        val result = ReplayPlanner.replay(steps, emptyMap(), provider)

        assertEquals(ReplayResult.Completed, result)
        assertEquals(listOf("id/search_field"), provider.imeEnterCalls)
        assertTrue(provider.clickCalls.isEmpty()) // never falls back to a CLICK
    }

    @Test
    fun `SUBMIT_SEARCH whose performImeEnter call itself fails is Stuck, not silently treated as success`() {
        val steps = listOf(submitSearchStep(1, "id/search_field"))
        val provider = FakeNodeProvider(
            emptyMap(), clearSignals,
            imeEnterApiSupported = true,
            imeFailingNodes = setOf("id/search_field")
        )
        val result = ReplayPlanner.replay(steps, emptyMap(), provider)

        assertTrue(result is ReplayResult.Stuck)
        assertEquals(1, (result as ReplayResult.Stuck).atStepOrder)
        assertEquals(listOf("id/search_field"), provider.imeEnterCalls) // it WAS attempted...
    }

    @Test
    fun `SUBMIT_SEARCH still halts on a payment screen -- the gate check is not bypassed or double-run`() {
        val steps = listOf(
            clickStep(1, "id/menu"),
            submitSearchStep(2, "id/search_field")
        )
        val signalsByCall = mapOf(1 to clearSignals, 2 to paymentSignals)
        val provider = FakeNodeProvider(signalsByCall, defaultSignals = paymentSignals, imeEnterApiSupported = true)
        val result = ReplayPlanner.replay(steps, emptyMap(), provider)

        assertTrue(result is ReplayResult.Halted)
        assertEquals(2, (result as ReplayResult.Halted).atStepOrder)
        // The gate blocked before SUBMIT_SEARCH's own capability check ever
        // ran -- proves the gate isn't special-cased away for this step.
        assertEquals(0, provider.imeEnterApiSupportedCallCount)
        assertTrue(provider.imeEnterCalls.isEmpty())
    }

    @Ignore("Replay-side duplicate-CLICK guard removed 2026-09-30 — see ReplayPlanner's REMOVED note. Duplicate capture is prevented at teach time (TapDedup/TouchClaim). Rewrite or delete these post-submission.")
    @Test
    fun `duplicate detection is scoped to CLICK only -- a repeated SCROLL on the same anchor is NOT deduplicated`() {
        // A taught "scroll down twice" on the same list is a normal,
        // intentional pattern (unlike a duplicate CLICK, which is always a
        // capture artifact) -- this fix must not break it.
        val scrollStep = { order: Int -> FlowStep(order = order, action = ActionType.SCROLL, target = ElementAnchor(resourceId = "id/results_list")) }
        val steps = listOf(scrollStep(1), scrollStep(2))
        val provider = FakeNodeProvider(emptyMap(), clearSignals)
        val result = ReplayPlanner.replay(steps, emptyMap(), provider)

        assertEquals(ReplayResult.Completed, result)
        assertEquals(listOf("id/results_list", "id/results_list"), provider.scrollCalls)
        assertEquals(listOf("id/results_list", "id/results_list"), provider.findNodeCalls)
    }

    @Test
    fun `a screen-settle wait slower than the 5s step timeout still completes`() {
        // ReplayEngine.waitForStableScreen can legitimately run ~6.3s+ in :app.
        // The tap already landed; the settle wait must not be cut off by the
        // 5s node-resolution budget.
        val inner = FakeNodeProvider(emptyMap(), clearSignals)
        val slowSettle = object : NodeProvider by inner {
            override fun awaitScreenChange() {
                Thread.sleep(7_000)
            }
        }
        val result = ReplayPlanner.replay(listOf(clickStep(1, "id/menu")), emptyMap(), slowSettle)

        assertEquals(ReplayResult.Completed, result)
        assertEquals(listOf("id/menu"), inner.clickCalls)
    }
}
