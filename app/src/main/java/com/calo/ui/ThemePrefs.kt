package com.calo.ui

import android.content.Context
import androidx.annotation.StyleRes
import com.calo.R

/**
 * Which of the two concrete themes (see values/themes.xml) is active.
 * Stored as a plain string in its own SharedPreferences file so any Activity
 * can read/write it without touching Room or any other subsystem.
 */
object ThemePrefs {

    enum class Theme(val prefValue: String, @StyleRes val styleRes: Int) {
        DEFAULT("default", R.style.Theme_Calo_Default),
        BLACK_WHITE("blackwhite", R.style.Theme_Calo_BlackWhite);

        companion object {
            fun fromPrefValue(value: String?): Theme = entries.find { it.prefValue == value } ?: DEFAULT
        }
    }

    private const val PREFS_NAME = "calo_theme_prefs"
    private const val KEY_THEME = "theme"

    fun get(context: Context): Theme {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return Theme.fromPrefValue(prefs.getString(KEY_THEME, null))
    }

    fun set(context: Context, theme: Theme) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_THEME, theme.prefValue)
            .apply()
    }
}
