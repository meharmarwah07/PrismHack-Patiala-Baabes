package com.calo.ui

import android.os.Bundle
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout

/**
 * Every Calo screen extends this instead of AppCompatActivity directly, so
 * the Palette (Default/BlackWhite) × Mode (Dark/Light) choice (ThemePrefs)
 * applies consistently everywhere without each Activity repeating the same
 * setTheme() call — and so the system back button closes an open drawer
 * instead of exiting the Activity (see [drawerLayout] below; found missing
 * 29 Sep 2026 by actually pressing back with the drawer open on-device —
 * it exited straight to the launcher, since DrawerLayout does NOT intercept
 * the system back gesture on its own).
 *
 * setTheme() has to run before super.onCreate() — AppCompat resolves theme
 * attributes while inflating its window during that call, so setting it
 * after would be too late for the very first layout pass.
 *
 * onResume() re-checks the stored preference and recreate()s if it changed
 * since this Activity was created — e.g. MainActivity is still on the back
 * stack, the user opens Themes and switches to BlackWhite (or flips Dark
 * Mode in the drawer), then presses back: MainActivity should come back
 * re-skinned, not stuck on whatever it was drawn with at launch.
 */
abstract class CaloBaseActivity : AppCompatActivity() {

    /**
     * Each subclass assigns this right after inflating its layout (every
     * activity_*.xml root is a DrawerLayout). Nullable because it's unset
     * for the brief window between super.onCreate() (where the back
     * callback below is registered) and setContentView() in the subclass —
     * the callback reads it lazily, at press time, not at registration time.
     */
    protected var drawerLayout: DrawerLayout? = null

    private var appliedState: ThemePrefs.State? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        val state = ThemePrefs.get(this)
        setTheme(state.styleRes)
        appliedState = state
        super.onCreate(savedInstanceState)

        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    val drawer = drawerLayout
                    if (drawer != null && drawer.isDrawerOpen(GravityCompat.START)) {
                        drawer.closeDrawer(GravityCompat.START)
                    } else {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                    }
                }
            }
        )
    }

    override fun onResume() {
        super.onResume()
        val current = ThemePrefs.get(this)
        if (current != appliedState) {
            recreate()
        }
    }
}
