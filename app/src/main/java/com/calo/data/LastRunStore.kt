package com.calo.data

import android.content.Context
import com.calo.R

/**
 * Persists the outcome of the most recent replay so it survives Activity
 * recreation and can be shown ("Last run: ...") after the fact. SharedPreferences
 * only -- deliberately not a Room entity, so there is no schema migration to get wrong.
 */
class LastRunStore(context: Context) {

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun record(flowDescription: String, outcome: String) {
        prefs.edit()
            .putString(KEY_DESCRIPTION, flowDescription)
            .putString(KEY_OUTCOME, outcome)
            .putLong(KEY_TIME, System.currentTimeMillis())
            .apply()
    }

    fun lastRunSummary(): String? {
        val outcome = prefs.getString(KEY_OUTCOME, null) ?: return null
        val description = prefs.getString(KEY_DESCRIPTION, null).orEmpty()
        val time = prefs.getLong(KEY_TIME, 0L)
        return appContext.getString(R.string.last_run_summary, description, outcome, relativeTime(time))
    }

    private fun relativeTime(thenMs: Long): String {
        val minutes = ((System.currentTimeMillis() - thenMs).coerceAtLeast(0L)) / 60_000L
        return when {
            minutes < 1 -> appContext.getString(R.string.last_run_just_now)
            minutes < 60 -> appContext.getString(R.string.last_run_minutes_ago, minutes)
            minutes < 60 * 24 -> appContext.getString(R.string.last_run_hours_ago, minutes / 60)
            else -> appContext.getString(R.string.last_run_days_ago, minutes / (60 * 24))
        }
    }

    private companion object {
        const val PREFS_NAME = "calo_last_run"
        const val KEY_DESCRIPTION = "description"
        const val KEY_OUTCOME = "outcome"
        const val KEY_TIME = "time_ms"
    }
}
