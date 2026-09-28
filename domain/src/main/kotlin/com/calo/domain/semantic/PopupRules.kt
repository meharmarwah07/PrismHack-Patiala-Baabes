package com.calo.domain.semantic

/**
 * Recognises the "get this out of my way" button on an interrupting pop-up
 * (app-rating prompts, promo sheets, "turn on notifications?"), so replay
 * can clear one pop-up and try the step again instead of going Stuck.
 *
 * Only buttons whose ENTIRE label is a known dismiss phrase count — never
 * a substring match, so "Close account" or "Skip delivery" can't qualify.
 * Deliberately excluded: "Allow" (would grant a permission), "Cancel" (can
 * cancel an order), "OK"/"Continue"/"Yes" (confirm whatever the pop-up is
 * asking). Those change something; everything listed here only declines.
 */
object PopupRules {

    private val DISMISS_LABELS = setOf(
        "not now", "skip", "no thanks", "no, thanks", "maybe later", "later",
        "close", "dismiss", "got it", "ok, got it", "×", "✕", "x"
    )

    fun findDismissButton(elements: List<ScreenElement>): ScreenElement? =
        elements.firstOrNull { e ->
            e.clickable && !e.editable &&
                (RoleKeywords.normalize(e.label) in DISMISS_LABELS ||
                    RoleKeywords.normalize(e.contentDescription) in DISMISS_LABELS)
        }
}
