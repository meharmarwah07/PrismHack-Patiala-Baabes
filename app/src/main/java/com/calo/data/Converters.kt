package com.calo.data

import androidx.room.TypeConverter
import com.calo.domain.model.FlowStep
import com.calo.domain.model.SlotDefinition
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

/**
 * Room can't store a List<FlowStep> directly, so this turns it into a JSON
 * string going in and back into objects coming out. If a step or slot ever
 * changes shape, this is the one file that breaks — which is the point:
 * every other file only ever touches the Kotlin objects, never raw JSON.
 */
class Converters {
    private val json = Json { ignoreUnknownKeys = true }

    @TypeConverter
    fun stepsToJson(steps: List<FlowStep>): String = json.encodeToString(steps)

    @TypeConverter
    fun jsonToSteps(value: String): List<FlowStep> = json.decodeFromString(value)

    @TypeConverter
    fun slotsToJson(slots: List<SlotDefinition>): String = json.encodeToString(slots)

    @TypeConverter
    fun jsonToSlots(value: String): List<SlotDefinition> = json.decodeFromString(value)
}
