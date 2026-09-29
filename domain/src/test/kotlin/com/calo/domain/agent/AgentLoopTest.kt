package com.calo.domain.agent

import com.calo.domain.gate.ScreenSignals
import com.calo.domain.model.ActionType
import com.calo.domain.model.ElementAnchor
import com.calo.domain.model.FlowStep
import com.calo.domain.replay.NodeHandle
import com.calo.domain.replay.NodeProvider
import com.calo.domain.replay.ReplayResult
import com.calo.domain.semantic.ScreenElement
import com.calo.domain.semantic.SemanticRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private class Node(val id: Int) : NodeHandle

/** A screen that changes as the fake agent acts: [screens] indexed by how many actions have run. */
private class FakeScreen(
    private val screens: List<List<ScreenElement>>,
    private val signalsAfter: Map<Int, ScreenSignals> = emptyMap()
) : NodeProvider {
    private val clear = ScreenSignals(packageName = "com.android.chrome", allText = listOf("skribbl"))
    var actions = 0
    val clicks = mutableListOf<Int>()
    val typed = mutableListOf<Pair<Int, String>>()

    private fun current() = screens[minOf(actions, screens.lastIndex)]

    override fun currentScreenSignals() = signalsAfter[actions] ?: clear
    override fun findNode(anchor: ElementAnchor): NodeHandle? = null
    override fun findNodeByValue(value: String): NodeHandle? =
        current().firstOrNull { it.label == value }?.let { Node(it.id) }
    override fun screenElements() = current()
    override fun nodeForElement(element: ScreenElement): NodeHandle = Node(element.id)
    override fun performClick(node: NodeHandle): Boolean { clicks += (node as Node).id; actions++; return true }
    override fun performSetText(node: NodeHandle, value: String): Boolean { typed += (node as Node).id to value; actions++; return true }
    override fun performScroll(node: NodeHandle, forward: Boolean) = true
    override fun awaitIdle() {}
}

class AgentLoopTest {

    private val task = AgentTask(goal = "\"games\"", appName = "Chrome", hints = listOf("tap \"Skribbl\"", "tap \"Play!\""), hintsAlreadyDone = 0)

    private fun scripted(vararg replies: String): (String) -> String? {
        val queue = ArrayDeque(replies.toList())
        return { queue.removeFirstOrNull() }
    }

    @Test
    fun `follows the AI's actions until it says done`() {
        val screen = FakeScreen(listOf(
            listOf(ScreenElement(id = 0, label = "Go to Google Home", clickable = true), ScreenElement(id = 1, label = "Skribbl", clickable = true)),
            listOf(ScreenElement(id = 0, label = "Play!", clickable = true))
        ))
        val result = AgentLoop.run(task, 5, screen, scripted("""{"action":"tap","id":1}""", """{"action":"tap","id":0}""", """{"action":"done"}"""))
        assertEquals(ReplayResult.Completed, result)
        assertEquals(listOf(1, 0), screen.clicks)
    }

    @Test
    fun `safety gate halts before the AI's next action, even mid-run`() {
        val payment = ScreenSignals(packageName = "com.android.chrome", allText = listOf("Enter your CVV"))
        val screen = FakeScreen(
            listOf(listOf(ScreenElement(id = 0, label = "Next", clickable = true))),
            signalsAfter = mapOf(1 to payment)
        )
        val result = AgentLoop.run(task, 5, screen, scripted("""{"action":"tap","id":0}""", """{"action":"tap","id":0}"""))
        assertTrue(result is ReplayResult.Halted)
        assertEquals(1, screen.clicks.size) // the second tap never happened
    }

    @Test
    fun `stops after the action budget instead of looping forever`() {
        val screen = FakeScreen(listOf(listOf(ScreenElement(id = 0, label = "Something", clickable = true))))
        val always: (String) -> String? = { """{"action":"tap","id":0}""" }
        val result = AgentLoop.run(task, 5, screen, always, maxActions = 3)
        assertTrue(result is ReplayResult.Stuck)
        assertEquals(3, screen.clicks.size)
    }

    @Test
    fun `gives up after repeated garbage replies`() {
        val screen = FakeScreen(listOf(emptyList()))
        val result = AgentLoop.run(task, 5, screen, scripted("sure! I'd tap play", "hmm", """{"action":"done"}"""))
        assertTrue(result is ReplayResult.Stuck)
    }

    @Test
    fun `unreachable AI is Stuck, nothing touched`() {
        val screen = FakeScreen(listOf(listOf(ScreenElement(id = 0, label = "Play!", clickable = true))))
        val result = AgentLoop.run(task, 5, screen, { null })
        assertTrue(result is ReplayResult.Stuck)
        assertTrue(screen.clicks.isEmpty())
    }

    @Test
    fun `typing only goes into a real text field`() {
        val screen = FakeScreen(listOf(listOf(
            ScreenElement(id = 0, label = "Play!", clickable = true),
            ScreenElement(id = 1, hintText = "Search", editable = true)
        )))
        AgentLoop.run(task, 5, screen, scripted("""{"action":"type","id":0,"text":"x"}""", """{"action":"type","id":1,"text":"skribbl.io"}""", """{"action":"done"}"""))
        assertEquals(listOf(1 to "skribbl.io"), screen.typed)
    }

    // ---- AgentResponseParser

    @Test
    fun `parses every action shape, with or without fences`() {
        assertEquals(AgentAction.Tap(3), AgentResponseParser.parse("""{"action":"tap","id":3}"""))
        assertEquals(AgentAction.Tap(3), AgentResponseParser.parse("```json\n{\"action\":\"tap\",\"id\":\"3\"}\n```"))
        assertEquals(AgentAction.TapText("Skribbl"), AgentResponseParser.parse("""{"action":"tap_text","text":"Skribbl"}"""))
        assertEquals(AgentAction.Type(1, "skribbl.io"), AgentResponseParser.parse("""{"action":"type","id":1,"text":"skribbl.io"}"""))
        assertEquals(AgentAction.Scroll(forward = false), AgentResponseParser.parse("""{"action":"scroll","direction":"up"}"""))
        assertEquals(AgentAction.Done, AgentResponseParser.parse("""Here you go: {"action":"done"}"""))
        assertEquals(AgentAction.GiveUp("login needed"), AgentResponseParser.parse("""{"action":"give_up","reason":"login needed"}"""))
        assertNull(AgentResponseParser.parse("I would tap Play"))
        assertNull(AgentResponseParser.parse("""{"action":"type","text":"no id"}"""))
    }

    // ---- AgentTaskBuilder

    @Test
    fun `hints come from the taught steps, skipping junk labels, and mark what replay already did`() {
        val steps = listOf(
            FlowStep(1, ActionType.CLICK, ElementAnchor(text = "Search Google or type URL")),
            FlowStep(2, ActionType.SET_TEXT, ElementAnchor(hintText = "Search Google or type URL"), recordedValue = "skribble"),
            FlowStep(3, ActionType.CLICK, ElementAnchor(contentDescription = "Skribbl https://skribbl.io Skribbl")),
            FlowStep(4, ActionType.CLICK, ElementAnchor(text = "Play!")),
            FlowStep(5, ActionType.CLICK, ElementAnchor(contentDescription = "a1iBuLkeCK5gJws3WtOGOJV6L9xd+yNsf8CGGn3VmqTMmwAAAAASUVORK5CYII="))
        )
        val task = AgentTaskBuilder.from("games", "games", "games", "Chrome", steps, emptyMap(), stuckAtOrder = 4)
        assertEquals(
            listOf("tap \"Search Google or type URL\"", "type \"skribble\" into \"Search Google or type URL\"", "tap \"Skribbl https://skribbl.io Skribbl\"", "tap \"Play!\""),
            task.hints
        )
        assertEquals(3, task.hintsAlreadyDone)
        assertEquals("\"games\"", task.goal)
        assertFalse(AgentPrompt.build(task, emptyList(), emptyList(), emptyList(), 10).contains("a1iBuLke"))
    }

    @Test
    fun `a CLICK step with no readable label still gets a hint from its role, not dropped silently`() {
        // Regression guard (29 Sep 2026, Google widget "hello" flow, on-device):
        // this exact shape (role=SELECT_RESULT, a totally blank anchor) used
        // to vanish from the hint list, leaving the AI helper with no idea
        // it needed to select something after typing the search query.
        val steps = listOf(
            FlowStep(1, ActionType.SET_TEXT, ElementAnchor(hintText = "Ask Google"), recordedValue = "skribbl"),
            FlowStep(2, ActionType.CLICK, ElementAnchor(), role = SemanticRole.SELECT_RESULT),
            FlowStep(3, ActionType.CLICK, ElementAnchor(text = "Play!"))
        )
        val task = AgentTaskBuilder.from("hello", "hello", "hello", "Google", steps, emptyMap(), stuckAtOrder = 1)
        assertEquals(
            listOf(
                "type \"skribbl\" into \"Ask Google\"",
                "select the search result/suggestion that matches what was just typed",
                "tap \"Play!\""
            ),
            task.hints
        )
    }

    @Test
    fun `a SUBMIT_SEARCH step gets a plain hint too`() {
        val steps = listOf(FlowStep(1, ActionType.SUBMIT_SEARCH, ElementAnchor()))
        val task = AgentTaskBuilder.from("play", "play", "play", "Chrome", steps, emptyMap(), stuckAtOrder = 1)
        assertEquals(listOf("press the keyboard's search/enter key to submit"), task.hints)
    }

    @Test
    fun `prompt tells the model the trigger phrase is a name, not literal content to search for`() {
        // Regression guard (29 Sep 2026, Google widget "hello" flow, on-device):
        // a flow named "hello" whose hint says to type "skribbl" got derailed
        // when the model tapped an on-screen "hello" search suggestion instead
        // of typing "skribbl" as hinted — it read the old "Task: "hello"..."
        // phrasing as an instruction to search for the literal word "hello".
        val task = AgentTask(goal = "\"hello\"", appName = "Google", hints = listOf("type \"skribbl\" into \"Ask Google\"", "tap \"Play!\""), hintsAlreadyDone = 0)
        val prompt = AgentPrompt.build(task, emptyList(), emptyList(), emptyList(), 10)
        assertFalse(prompt.contains("Task: \"hello\""))
        assertTrue(prompt.contains("IGNORE its wording as content"))
        assertTrue(prompt.contains("never tap a shortcut/suggestion/history item"))
    }
}
