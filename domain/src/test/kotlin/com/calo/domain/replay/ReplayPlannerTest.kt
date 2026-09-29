package com.calo.domain.replay

import com.calo.domain.gate.ScreenSignals
import com.calo.domain.model.ActionType
import com.calo.domain.model.ElementAnchor
import com.calo.domain.model.FlowStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
    private val availableOptions: Set<String> = emptySet() // text values findNodeByValue can find on screen
) : NodeProvider {
    private var callCount = 0
    val clickCalls = mutableListOf<String>()
    val setTextCalls = mutableListOf<Pair<String, String>>()
    val scrollCalls = mutableListOf<String>()
    var idleCalls = 0

    override fun currentScreenSignals(): ScreenSignals {
        callCount++
        return screenSignalsByCall[callCount] ?: defaultSignals
    }

    override fun findNode(anchor: ElementAnchor): NodeHandle? {
        val id = anchor.resourceId ?: anchor.text ?: "unknown"
        if (id in missingAnchors) return null
        return FakeNode(id)
    }

    override fun findNodeByValue(value: String): NodeHandle? {
        if (value !in availableOptions) return null
        return FakeNode("option:$value")
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
    fun `a web link recorded twice (link, then its title) skips the second copy and carries on`() {
        // Chrome / Google results, 27 Sep 2026: one tap on the Skribbl result
        // saved as the link and again as its title; the site is already open
        // by the second, so it can't be found — the flow must still reach Play!.
        val steps = listOf(
            FlowStep(order = 1, action = ActionType.CLICK, target = ElementAnchor(contentDescription = "Skribbl https://skribbl.io Skribbl")),
            FlowStep(order = 2, action = ActionType.CLICK, target = ElementAnchor(resourceId = "_Z064_45", text = "Skribbl")),
            FlowStep(order = 3, action = ActionType.CLICK, target = ElementAnchor(text = "Play!"))
        )
        val provider = FakeNodeProvider(emptyMap(), clearSignals, missingAnchors = setOf("_Z064_45"))
        assertEquals(ReplayResult.Completed, ReplayPlanner.replay(steps, emptyMap(), provider))
        assertEquals(listOf("unknown", "Play!"), provider.clickCalls)
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
        val provider = FakeNodeProvider(emptyMap(), clearSignals, availableOptions = setOf("Work"))
        val result = ReplayPlanner.replay(steps, mapOf("address" to "Work"), provider)

        assertEquals(ReplayResult.Completed, result)
        assertEquals(listOf("option:Work"), provider.clickCalls)
        assertTrue(provider.clickCalls.none { it == "id/address_row" })
    }

    @Test
    fun `CLICK slot value with no matching option on screen is Stuck, never falls back to the wrong original tap`() {
        val steps = listOf(clickStepWithSlot(1, "id/address_row", recordedText = "Home", slotName = "address"))
        val provider = FakeNodeProvider(emptyMap(), clearSignals, availableOptions = emptySet())
        val result = ReplayPlanner.replay(steps, mapOf("address" to "Work"), provider)

        assertTrue(result is ReplayResult.Stuck)
        assertEquals(1, (result as ReplayResult.Stuck).atStepOrder)
        assertTrue(result.reason.contains("Work"))
        assertTrue(provider.clickCalls.isEmpty()) // no wrong tap attempted either
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
        val provider = FakeNodeProvider(emptyMap(), clearSignals, availableOptions = setOf("Work"))
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
}
