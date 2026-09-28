package com.calo.ui

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

/**
 * Every Calo screen extends this instead of AppCompatActivity directly, so
 * the Default/BlackWhite choice (ThemePrefs) applies consistently everywhere
 * without each Activity repeating the same setTheme() call.
 *
 * setTheme() has to run before super.onCreate() — AppCompat resolves theme
 * attributes while inflating its window during that call, so setting it
 * after would be too late for the very first layout pass.
 *
 * onResume() re-checks the stored preference and recreate()s if it changed
 * since this Activity was created — e.g. MainActivity is still on the back
 * stack, the user opens Themes and switches to BlackWhite, then presses
 * back: MainActivity should come back re-skinned, not stuck on whatever it
 * was drawn with at launch.
 */
abstract class CaloBaseActivity : AppCompatActivity() {

    private var appliedTheme: ThemePrefs.Theme? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        val theme = ThemePrefs.get(this)
        setTheme(theme.styleRes)
        appliedTheme = theme
        super.onCreate(savedInstanceState)
    }

    override fun onResume() {
        super.onResume()
        val current = ThemePrefs.get(this)
        if (current != appliedTheme) {
            recreate()
        }
    }
}
