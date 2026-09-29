package com.calo.ui

import android.content.Context
import androidx.annotation.StyleRes
import com.calo.R

/**
 * Which of the four concrete themes (see values/themes.xml) is active —
 * a Palette (Default vs BlackWhite: which marble/accent) crossed with a
 * Mode (Dark vs Light: wired to the drawer's Dark Mode switch). Stored as
 * two plain strings in their own SharedPreferences file so any Activity
 * can read/write either independently without touching Room or any other
 * subsystem.
 */
object ThemePrefs {

    enum class Palette(val prefValue: String) {
        DEFAULT("default"),
        BLACK_WHITE("blackwhite");

        companion object {
            fun fromPrefValue(value: String?): Palette = entries.find { it.prefValue == value } ?: DEFAULT
        }
    }

    enum class Mode(val prefValue: String) {
        DARK("dark"),
        LIGHT("light");

        companion object {
            fun fromPrefValue(value: String?): Mode = entries.find { it.prefValue == value } ?: DARK
        }
    }

    /** The resolved (palette, mode) pair, plus which style resource that combination maps to. */
    data class State(val palette: Palette, val mode: Mode) {
        @get:StyleRes
        val styleRes: Int
            get() = when (palette to mode) {
                Palette.DEFAULT to Mode.DARK -> R.style.Theme_Calo_DefaultDark
                Palette.DEFAULT to Mode.LIGHT -> R.style.Theme_Calo_DefaultLight
                Palette.BLACK_WHITE to Mode.DARK -> R.style.Theme_Calo_BlackWhiteDark
                else -> R.style.Theme_Calo_BlackWhiteLight
            }
    }

    private const val PREFS_NAME = "calo_theme_prefs"
    private const val KEY_PALETTE = "palette"
    private const val KEY_MODE = "mode"

    fun get(context: Context): State {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return State(
            Palette.fromPrefValue(prefs.getString(KEY_PALETTE, null)),
            Mode.fromPrefValue(prefs.getString(KEY_MODE, null))
        )
    }

    fun setPalette(context: Context, palette: Palette) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_PALETTE, palette.prefValue)
            .apply()
    }

    fun setMode(context: Context, mode: Mode) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_MODE, mode.prefValue)
            .apply()
    }
}
