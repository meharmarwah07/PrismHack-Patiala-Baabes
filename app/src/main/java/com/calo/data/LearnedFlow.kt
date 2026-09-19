package com.calo.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.TypeConverters
import com.calo.domain.model.FlowStep
import com.calo.domain.model.SlotDefinition
import java.util.UUID

/**
 * One taught recipe. This is the single object the teach pipeline writes
 * and the replay pipeline reads — the handoff point between them. If two
 * teammates are building teach and replay in parallel, this file is what
 * they both need to agree on FIRST.
 */
@Entity(tableName = "learned_flows")
@TypeConverters(Converters::class)
data class LearnedFlow(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val targetPackage: String,        // e.g. "com.dominos.app" — which app this flow runs on
    val triggerUtterance: String,     // the exact sentence spoken while teaching
    val description: String,         // human-readable label, e.g. shown in "Learned: ..."
    val steps: List<FlowStep>,        // stored as JSON via Converters, see below
    val slots: List<SlotDefinition>,  // stored as JSON via Converters, see below
    val createdAt: Long = System.currentTimeMillis()
)
