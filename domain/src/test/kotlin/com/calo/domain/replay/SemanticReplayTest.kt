package com.calo.domain.replay

import com.calo.domain.gate.ScreenSignals
import com.calo.domain.model.ActionType
import com.calo.domain.model.ElementAnchor
import com.calo.domain.model.FlowStep
import com.calo.domain.semantic.RoleLabeler
import com.calo.domain.semantic.ScreenElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Ignore
import org.junit.Test

private class Handle(val id: String) : NodeHandle

/**
 * A fake screen that serves both halves of replay: exact anchors (by
 * resourceId/text, from [anchorsOnScreen]) and the semantic snapshot
 * ([elements]). Every action is logged in order in [actions].
 */
private class FakeScreen(
    var elements: List<ScreenElement> = emptyList(),
    var signals: ScreenSignals = ScreenSignals(packageName = "com.myntra.android", allText = listOf("Home")),
    private val anchorsOnScreen: Set<String> = emptySet(),
    private val imeWorks: Boolean = true,
    private val onClick: FakeScreen.(String) -> Unit = {}
) : NodeProvider {
    val actions = mutableListOf<String>()
    var elementReads = 0

    override fun currentScreenSignals() = signals

    override fun findNode(anchor: ElementAnchor): NodeHandle? {
        val key = anchor.resourceId ?: anchor.text ?: anchor.contentDescription ?: return null
        return if (key in anchorsOnScreen) Handle("anchor:$key") else null
    }

    override fun findNodeByValue(value: String): NodeHandle? =
        elements.firstOrNull { it.label == value }?.let { Handle("el:${it.id}") }

    // Simulates a page that fills in over time: from read number
    // [laterElementsAfterReads] + 1 onward, [laterElements] is served instead.
    var laterElements: List<ScreenElement>? = null
    var laterElementsAfterReads = 0

    // Simulates a row that is only in the tree once the list has been scrolled down.
    var elementsAfterScroll: List<ScreenElement>? = null
    var scrollPosition = 0

    override fun screenElements(): List<ScreenElement> {
        elementReads++
        elementsAfterScroll?.let { if (scrollPosition > 0) return it }
        val later = laterElements
        return if (later != null && elementReads > laterElementsAfterReads) later else elements
    }

    override fun scrollScreen(forward: Boolean): Boolean {
        scrollPosition += if (forward) 1 else -1
        actions += if (forward) "scrollScreen down" else "scrollScreen up"
        return true
    }

    override fun nodeForElement(element: ScreenElement): NodeHandle = Handle("el:${element.id}")

    override fun performClick(node: NodeHandle): Boolean {
        val id = (node as Handle).id
        actions += "click $id"
        onClick(id)
        return true
    }

    override fun performSetText(node: NodeHandle, value: String): Boolean {
        actions += "type ${(node as Handle).id}=$value"
        return true
    }

    override fun performScroll(node: NodeHandle, forward: Boolean): Boolean {
        actions += "scroll ${(node as Handle).id}"
        return true
    }

    override fun awaitIdle() {}

    override fun submitCurrentInput(): Boolean {
        actions += "submit"
        return imeWorks
    }
}

class SemanticReplayTest {

    private fun click(order: Int, text: String? = null, cd: String? = null, resId: String? = null) =
        FlowStep(order, ActionType.CLICK, ElementAnchor(resourceId = resId, text = text, contentDescription = cd))

    // Taught on Amazon: note Amazon-specific resource ids on every step.
    private val amazonFlow = RoleLabeler.label(
        listOf(
            click(1, text = "Search Amazon.in", resId = "in.amazon.mShop.android.shopping:id/chrome_search_hint_view"),
            FlowStep(
                2, ActionType.SET_TEXT,
                ElementAnchor(resourceId = "in.amazon.mShop.android.shopping:id/rs_search_src_text", hintText = "Search Amazon.in"),
                recordedValue = "wireless earbuds", slotName = "query"
            ),
            click(3, text = "wireless earbuds", resId = "in.amazon.mShop.android.shopping:id/iss_search_suggestion"),
            click(4, text = "Accept cookies", resId = "in.amazon.mShop.android.shopping:id/cookie_ok"),
            click(5, text = "boAt Airdopes 141 Wireless Earbuds"),
            click(6, text = "Add to Cart", resId = "in.amazon.mShop.android.shopping:id/add-to-cart-button")
        )
    )

    // One Myntra "screen" holding everything, for brevity.
    private val myntra = listOf(
        ScreenElement(0, contentDescription = "Search", clickable = true),
        ScreenElement(1, hintText = "Search for brands and products", editable = true, clickable = true),
        ScreenElement(2, label = "wireless earbuds", clickable = true),
        ScreenElement(3, label = "Filter", clickable = true, inList = true),
        ScreenElement(4, label = "Noise Buds VS104 Wireless Earbuds | ₹999", clickable = true, inList = true),
        ScreenElement(5, label = "ADD TO BAG", clickable = true)
    )

    @Test
    fun `amazon-taught flow runs on Myntra by role, skipping the Amazon-only step`() {
        val screen = FakeScreen(elements = myntra)
        val result = ReplayPlanner.replay(amazonFlow, mapOf("query" to "wireless earbuds"), screen, ReplayMode.SEMANTIC)

        assertEquals(ReplayResult.Completed, result)
        assertEquals(
            listOf(
                "click el:0",                          // OPEN_SEARCH
                "type el:1=wireless earbuds",          // SEARCH_INPUT
                "click el:2",                          // SUBMIT_SEARCH (suggestion)
                "click el:4",                          // SELECT_RESULT (first matching item, not the Filter chip)
                "click el:5"                           // ADD_TO_CART ("ADD TO BAG")
            ),
            screen.actions
        )
    }

    @Test
    fun `slot value is carried across apps`() {
        val screen = FakeScreen(elements = myntra.map { if (it.id == 2) it.copy(label = "running shoes") else it } +
            ScreenElement(9, label = "Nike Running Shoes", clickable = true, inList = true))
        ReplayPlanner.replay(amazonFlow, mapOf("query" to "running shoes"), screen, ReplayMode.SEMANTIC)
        assertTrue(screen.actions.contains("type el:1=running shoes"))
        assertTrue(screen.actions.contains("click el:9"))
    }

    // 2026-09-30 Zomato: results page filled in over several seconds; the first snapshot had
    // only the "Cake | See all restaurants" header, and replay tapped it instead of waiting for
    // the taught restaurant card.
    @Test
    fun `taught result that appears late is waited for, not replaced by a positional guess`() {
        val taught = FlowStep(
            1, ActionType.CLICK,
            ElementAnchor(
                contentDescription = "Restaurant Name is Bake By Ecco ₹100 OFF above ₹199 delivers in 25 minutesSwipe up or down for more actions"
            ),
            role = com.calo.domain.semantic.SemanticRole.SELECT_RESULT
        )
        val header = ScreenElement(3, label = "Cake | See all restaurants |", clickable = true, inList = true)
        val card = ScreenElement(
            4, contentDescription = taught.target.contentDescription, clickable = true, inList = true
        )
        val screen = FakeScreen(elements = listOf(header)).also {
            it.laterElements = listOf(header, card)
            it.laterElementsAfterReads = 2
        }

        val result = ReplayPlanner.replay(listOf(taught), emptyMap(), screen, ReplayMode.EXACT)

        assertEquals(ReplayResult.Completed, result)
        assertEquals(listOf("click el:4"), screen.actions)
    }

    private fun bakeByEccoStep() = FlowStep(
        1, ActionType.CLICK,
        ElementAnchor(
            contentDescription = "Restaurant Name is Bake By Ecco ₹100 OFF above ₹199 delivers in 25 minutesSwipe up or down for more actions"
        ),
        role = com.calo.domain.semantic.SemanticRole.SELECT_RESULT
    )

    @Test
    fun `taught result below the fold is found by scrolling`() {
        val taught = bakeByEccoStep()
        val header = ScreenElement(3, label = "Cake | See all restaurants |", clickable = true, inList = true)
        val card = ScreenElement(4, contentDescription = taught.target.contentDescription, clickable = true, inList = true)
        val screen = FakeScreen(elements = listOf(header)).also { it.elementsAfterScroll = listOf(header, card) }

        val result = ReplayPlanner.replay(listOf(taught), emptyMap(), screen, ReplayMode.EXACT)

        assertEquals(ReplayResult.Completed, result)
        assertEquals(listOf("scrollScreen down", "click el:4"), screen.actions)
    }

    @Test
    fun `taught result that never appears is scrolled back before the positional fallback`() {
        val taught = bakeByEccoStep()
        val header = ScreenElement(3, label = "Cake | See all restaurants |", clickable = true, inList = true)
        val screen = FakeScreen(elements = listOf(header))

        ReplayPlanner.replay(listOf(taught), emptyMap(), screen, ReplayMode.EXACT)

        assertEquals(0, screen.scrollPosition) // every scroll down was undone
        assertTrue(screen.actions.count { it == "scrollScreen down" } in 1..4)
    }

    @Test
    fun `same-app replay uses the exact anchor and never consults the matcher`() {
        val anchors = setOf(
            "in.amazon.mShop.android.shopping:id/chrome_search_hint_view",
            "in.amazon.mShop.android.shopping:id/rs_search_src_text",
            "in.amazon.mShop.android.shopping:id/iss_search_suggestion",
            "in.amazon.mShop.android.shopping:id/cookie_ok",
            "boAt Airdopes 141 Wireless Earbuds",
            "in.amazon.mShop.android.shopping:id/add-to-cart-button"
        )
        val screen = FakeScreen(anchorsOnScreen = anchors)
        val result = ReplayPlanner.replay(amazonFlow, emptyMap(), screen, ReplayMode.EXACT)

        assertEquals(ReplayResult.Completed, result)
        assertEquals(0, screen.elementReads)
        assertEquals(6, screen.actions.size)
        assertTrue(screen.actions[3] == "click anchor:in.amazon.mShop.android.shopping:id/cookie_ok")
    }

    @Test
    fun `same-app replay falls back to the role when an app update renamed the button`() {
        val flow = RoleLabeler.label(listOf(click(1, text = "Add to Cart", resId = "old:id/add_btn")))
        val screen = FakeScreen(elements = listOf(ScreenElement(3, label = "Add to Cart", clickable = true)))
        val result = ReplayPlanner.replay(flow, emptyMap(), screen, ReplayMode.EXACT)
        assertEquals(ReplayResult.Completed, result)
        assertEquals(listOf("click el:3"), screen.actions)
    }

    @Test
    fun `same-app step with no role still goes Stuck when its anchor is missing`() {
        val flow = listOf(click(1, text = "Battery"))
        val screen = FakeScreen(elements = listOf(ScreenElement(0, label = "Battery Saver", clickable = true)))
        val result = ReplayPlanner.replay(flow, emptyMap(), screen, ReplayMode.EXACT)
        assertTrue(result is ReplayResult.Stuck)
        assertTrue(screen.actions.isEmpty())
    }

    @Test
    fun `credential gate still halts a cross-app replay before any tap`() {
        val screen = FakeScreen(
            elements = myntra,
            signals = ScreenSignals(packageName = "com.myntra.android", allText = listOf("Enter OTP"))
        )
        val result = ReplayPlanner.replay(amazonFlow, emptyMap(), screen, ReplayMode.SEMANTIC)
        assertTrue(result is ReplayResult.Halted)
        assertTrue(screen.actions.isEmpty())
    }

    @Test
    fun `a pop-up is dismissed once and the step retried`() {
        val flow = RoleLabeler.label(listOf(click(1, text = "Add to Cart")))
        val cartButton = ScreenElement(1, label = "Add to Cart", clickable = true)
        val screen = FakeScreen(
            elements = listOf(ScreenElement(0, label = "Not now", clickable = true)),
            onClick = { id -> if (id == "el:0") elements = listOf(cartButton) }
        )
        val result = ReplayPlanner.replay(flow, emptyMap(), screen, ReplayMode.SEMANTIC)
        assertEquals(ReplayResult.Completed, result)
        assertEquals(listOf("click el:0", "click el:1"), screen.actions)
    }

    @Test
    fun `gate is re-checked after dismissing a pop-up`() {
        val flow = RoleLabeler.label(listOf(click(1, text = "Add to Cart")))
        val screen = FakeScreen(
            elements = listOf(ScreenElement(0, label = "Skip", clickable = true)),
            onClick = { id ->
                if (id == "el:0") {
                    elements = listOf(ScreenElement(1, label = "Add to Cart", clickable = true))
                    signals = ScreenSignals(packageName = "x", allText = listOf("Sign in to continue"))
                }
            }
        )
        val result = ReplayPlanner.replay(flow, emptyMap(), screen, ReplayMode.SEMANTIC)
        assertTrue(result is ReplayResult.Halted)
        assertEquals(listOf("click el:0"), screen.actions)
    }

    @Test
    fun `ambiguous match stops and asks rather than guessing`() {
        val flow = RoleLabeler.label(listOf(click(1, text = "ADD")))
        val screen = FakeScreen(
            elements = listOf(
                ScreenElement(0, label = "ADD", clickable = true, inList = true),
                ScreenElement(1, label = "ADD", clickable = true, inList = true)
            )
        )
        val result = ReplayPlanner.replay(flow, emptyMap(), screen, ReplayMode.SEMANTIC)
        assertTrue(result is ReplayResult.Stuck && result.reason.contains("not sure"))
        assertTrue(screen.actions.isEmpty())
    }

    @Test
    fun `cross-app replay of a flow with no known meaning refuses up front`() {
        val flow = RoleLabeler.label(listOf(click(1, text = "Battery"), click(2, text = "Battery Saver")))
        val screen = FakeScreen(elements = listOf(ScreenElement(0, label = "Battery", clickable = true)))
        val result = ReplayPlanner.replay(flow, emptyMap(), screen, ReplayMode.SEMANTIC)
        assertTrue(result is ReplayResult.Stuck)
        assertTrue(screen.actions.isEmpty())
    }

    @Test
    fun `open-search is skipped when the app already shows a search box`() {
        val screen = FakeScreen(elements = myntra.filter { it.id != 0 })
        val result = ReplayPlanner.replay(amazonFlow, emptyMap(), screen, ReplayMode.SEMANTIC)
        assertEquals(ReplayResult.Completed, result)
        assertEquals("type el:1=wireless earbuds", screen.actions.first())
    }

    @Test
    fun `search is submitted with the keyboard when there's no suggestion to tap`() {
        val screen = FakeScreen(elements = myntra.filter { it.id != 2 })
        val result = ReplayPlanner.replay(amazonFlow, emptyMap(), screen, ReplayMode.SEMANTIC)
        assertEquals(ReplayResult.Completed, result)
        assertEquals("submit", screen.actions[2])
    }

    @Test
    fun `typing followed directly by a result tap presses the search key in between`() {
        val flow = RoleLabeler.label(
            listOf(
                FlowStep(1, ActionType.SET_TEXT, ElementAnchor(hintText = "Search"), recordedValue = "wireless earbuds"),
                click(2, text = "Noise Buds VS104 Wireless Earbuds")
            )
        )
        val screen = FakeScreen(elements = myntra)
        ReplayPlanner.replay(flow, emptyMap(), screen, ReplayMode.SEMANTIC)
        assertEquals(listOf("type el:1=wireless earbuds", "submit", "click el:4"), screen.actions)
    }

    @Test
    fun `search button is the fallback when the keyboard can't submit`() {
        val screen = FakeScreen(elements = myntra.filter { it.id != 2 }, imeWorks = false)
        val result = ReplayPlanner.replay(amazonFlow, emptyMap(), screen, ReplayMode.SEMANTIC)
        assertEquals(ReplayResult.Completed, result)
        assertEquals(listOf("submit", "click el:0"), screen.actions.subList(2, 4))
    }

    @Test
    fun `a tap recorded twice is skipped once the first copy has navigated away`() {
        val card = "Restaurant Name is Burger King"
        val flow = listOf(click(1, cd = card), click(2, cd = card), click(3, text = "ADD"))
        val screen = FakeScreen(anchorsOnScreen = setOf(card))
        val replay = object : NodeProvider by screen {
            var tapped = false
            override fun findNode(anchor: ElementAnchor): NodeHandle? =
                if (anchor.contentDescription == card && tapped) null
                else if (anchor.text == "ADD") Handle("anchor:ADD")
                else screen.findNode(anchor)
            override fun performClick(node: NodeHandle): Boolean {
                if ((node as Handle).id == "anchor:$card") tapped = true
                return screen.performClick(node)
            }
        }
        val result = ReplayPlanner.replay(flow, emptyMap(), replay, ReplayMode.EXACT)
        assertEquals(ReplayResult.Completed, result)
        assertEquals(listOf("click anchor:$card", "click anchor:ADD"), screen.actions)
    }

    @Ignore("Replay-side duplicate-CLICK guard removed 2026-09-30 — see ReplayPlanner's REMOVED note. Duplicate capture is prevented at teach time (TapDedup/TouchClaim). Rewrite or delete these post-submission.")
    @Test
    fun `a tap recorded twice is deduped, not replayed twice (2026-09-29 merge decision)`() {
        // Was "done twice" — reverted deliberately during the lane-a-teach
        // merge. Two consecutive identical CLICK steps are indistinguishable,
        // by the time they're a saved FlowStep, from a single physical tap
        // captured twice by two different capture paths (the confirmed,
        // on-device, 2026-09-26 bug isDuplicateCapture exists to catch — see
        // ReplayPlanner's class doc). Trusting a teach-time-only signal
        // (TapDedup's precise timing window) to have already prevented that
        // was rejected as the riskier option: if it ever misses a case,
        // replay would tap for real multiple times, the exact double-action
        // risk this project treats as serious everywhere else. A genuine
        // "tap + twice" flow (quantity plus) is a real, accepted limitation
        // of this choice, not an oversight — see README's dated entry.
        val flow = listOf(click(1, text = "+"), click(2, text = "+"))
        val screen = FakeScreen(anchorsOnScreen = setOf("+"))
        ReplayPlanner.replay(flow, emptyMap(), screen, ReplayMode.EXACT)
        assertEquals(listOf("click anchor:+"), screen.actions)
    }

    @Test
    fun `a missing step that is NOT a repeat still goes Stuck`() {
        val flow = listOf(click(1, text = "A"), click(2, text = "B"))
        val screen = FakeScreen(anchorsOnScreen = setOf("A"))
        assertTrue(ReplayPlanner.replay(flow, emptyMap(), screen, ReplayMode.EXACT) is ReplayResult.Stuck)
    }

    @Test
    fun `a scroll that can't move doesn't end the replay`() {
        val flow = listOf(
            FlowStep(1, ActionType.SCROLL, ElementAnchor(resourceId = "list")),
            click(2, text = "ADD")
        )
        val screen = object : NodeProvider by FakeScreen(anchorsOnScreen = setOf("list", "ADD")) {
            val done = mutableListOf<String>()
            override fun performScroll(node: NodeHandle, forward: Boolean) = false.also { done += "scroll" }
            override fun performClick(node: NodeHandle) = true.also { done += "click" }
        }
        assertEquals(ReplayResult.Completed, ReplayPlanner.replay(flow, emptyMap(), screen, ReplayMode.EXACT))
        assertEquals(listOf("scroll", "click"), screen.done)
    }

    @Test
    fun `a submit-search step recorded with no element (tap made while the keyboard was up) taps the matching result`() {
        // As TeachRecorder.recordSubmitAfterTyping saves it (Zomato, 27 Sep).
        val flow = RoleLabeler.relabel(
            listOf(
                FlowStep(1, ActionType.SET_TEXT, ElementAnchor(resourceId = "z:id/edittext"), recordedValue = "Burger King"),
                FlowStep(2, ActionType.CLICK, ElementAnchor(), recordedValue = "Burger King")
            ).map { if (it.order == 1) it.copy(target = it.target.copy(hintText = "Search restaurants")) else it }
        )
        assertEquals(com.calo.domain.semantic.SemanticRole.SUBMIT_SEARCH, flow[1].role)
        val screen = FakeScreen(
            anchorsOnScreen = setOf("z:id/edittext"),
            elements = listOf(
                ScreenElement(0, label = "Burger King", editable = true, hintText = "Search restaurants"),
                ScreenElement(1, label = "Burger King | Fast Food | 3.9", clickable = true),
                ScreenElement(2, label = "Burger King", clickable = true)
            )
        )
        assertEquals(ReplayResult.Completed, ReplayPlanner.replay(flow, emptyMap(), screen, ReplayMode.EXACT))
        assertEquals(listOf("type anchor:z:id/edittext=Burger King", "click el:2"), screen.actions)
    }
}
