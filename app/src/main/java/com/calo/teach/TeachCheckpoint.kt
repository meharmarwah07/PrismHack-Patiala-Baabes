package com.calo.teach

import android.content.Context
import android.util.Log
import com.calo.domain.model.FlowStep
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Keeps an in-progress teaching session on disk, so a process kill
 * mid-teach (KNOWN_LIMITATIONS.md, 25 Sep: the service's process was
 * silently replaced mid-session and every recorded step was lost) no
 * longer throws the whole session away. CaloAccessibilityService writes
 * after every change to the step list and, when the system reconnects the
 * service in a fresh process, resumes teaching from here.
 *
 * The file existing at all means "a teaching session is in progress" —
 * it's written (empty) the moment teaching starts and deleted when it
 * stops, saved or not.
 */
class TeachCheckpoint(context: Context) {

    @Serializable
    data class Saved(
        val targetPackage: String? = null,
        val steps: List<FlowStep> = emptyList(),
        val savedAt: Long = 0
    )

    private val file = File(context.filesDir, "teach_checkpoint.json")
    private val tmp = File(context.filesDir, "teach_checkpoint.json.tmp")
    private val json = Json { ignoreUnknownKeys = true }

    fun save(steps: List<FlowStep>, targetPackage: String?) {
        try {
            tmp.writeText(json.encodeToString(Saved(targetPackage, steps, System.currentTimeMillis())))
            // Rename is atomic: a kill mid-write can't leave a half-written checkpoint.
            if (!tmp.renameTo(file)) {
                file.delete()
                tmp.renameTo(file)
            }
        } catch (e: Exception) {
            Log.w("Calo", "Couldn't write teach checkpoint", e)
        }
    }

    /** The in-progress session, or null if none (or it's older than [maxAgeMs] — the user has moved on). */
    fun load(maxAgeMs: Long): Saved? {
        if (!file.exists()) return null
        val saved = try {
            json.decodeFromString<Saved>(file.readText())
        } catch (e: Exception) {
            Log.w("Calo", "Discarding unreadable teach checkpoint", e)
            clear()
            return null
        }
        if (System.currentTimeMillis() - saved.savedAt > maxAgeMs) {
            clear()
            return null
        }
        return saved
    }

    fun clear() {
        file.delete()
        tmp.delete()
    }
}
