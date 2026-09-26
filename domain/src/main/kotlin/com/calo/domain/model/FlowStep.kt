package com.calo.domain.model

import com.calo.domain.semantic.SemanticRole
import kotlinx.serialization.Serializable

enum class ActionType {
    CLICK,      // ACTION_CLICK on the resolved node
    SET_TEXT,   // ACTION_SET_TEXT — typing into a field
    SCROLL,     // ACTION_SCROLL_FORWARD / BACKWARD
    WAIT        // pause for the screen to settle before resolving the next node
}

/**
 * One line on the recipe card. `target` is how we find the element again.
 * `slotName`, if set, means "don't use recordedValue verbatim — substitute
 * whatever the current slot value is instead." That's the entire mechanism
 * that turns a one-off recording into something that generalizes (T4–T6).
 */
@Serializable
data class FlowStep(
    val order: Int,
    val action: ActionType,
    val target: ElementAnchor,
    val recordedValue: String? = null, // literal value seen during teaching (for SET_TEXT)
    val slotName: String? = null,      // if non-null, overrides recordedValue at replay time
    // What this step MEANS (search, add to cart, ...), assigned after
    // teaching by RoleLabeler. Null = no recognised meaning: the step still
    // replays exactly on its own app, but can't be carried to another app.
    // Defaulted so flows saved before this field existed still decode.
    val role: SemanticRole? = null,
    // For SELECT_RESULT only: which matching result to pick, 1-based.
    val roleIndex: Int? = null
)
