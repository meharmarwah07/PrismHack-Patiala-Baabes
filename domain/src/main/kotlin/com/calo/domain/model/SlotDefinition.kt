package com.calo.domain.model

import kotlinx.serialization.Serializable

/**
 * A fill-in-the-blank in a learned flow — e.g. "item", "quantity", "address".
 * exampleValue is what was actually said/typed during teaching, kept around
 * so anyone debugging this later can see what the slot originally held.
 */
@Serializable
data class SlotDefinition(
    val name: String,             // "item" | "quantity" | "address" | ...
    val exampleValue: String,     // "Margherita" — the value recorded during teaching
    val producedByStepIndex: Int  // which FlowStep's value this slot fills in
)
