package com.calo.ui

import android.app.Activity
import android.content.Intent
import android.content.res.ColorStateList
import android.util.TypedValue
import android.view.View
import android.widget.ImageView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.drawerlayout.widget.DrawerLayout
import com.calo.R

enum class NavDestination { DASHBOARD, THEMES, SETTINGS, HELP }

/**
 * Wires the included view_nav_drawer.xml for one Activity: highlights
 * [current], and navigates each row. Dashboard has no screen of its own yet
 * (per product decision, 29 Sep 2026) so it goes straight to
 * SavedWorkflowsActivity; Settings/Help have no mockup yet either, so they
 * get the same visible "isn't built yet" no-op MainActivity already used for
 * its own menu/profile buttons before this drawer existed — a silently dead
 * tap would read as a bug, not an unbuilt screen.
 */
class NavDrawerController(
    private val activity: Activity,
    private val drawerLayout: DrawerLayout,
    private val current: NavDestination?
) {
    fun setup() {
        // "Calo" header: same destination as the launcher icon. current == null
        // only for MainActivity itself (see its onCreate) — from there this is
        // just a close, not a relaunch. CLEAR_TOP + SINGLE_TOP drops any
        // screens stacked above the existing MainActivity instance and reuses
        // it (onNewIntent), rather than piling up a new one underneath.
        activity.findViewById<View>(R.id.navHomeTitle).setOnClickListener {
            drawerLayout.closeDrawers()
            if (current != null) {
                val intent = Intent(activity, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                }
                activity.startActivity(intent)
            }
        }

        highlight(NavDestination.DASHBOARD, R.id.navIconDashboard, R.id.navLabelDashboard)
        highlight(NavDestination.THEMES, null, R.id.navLabelThemes)
        highlight(NavDestination.SETTINGS, R.id.navIconSettings, R.id.navLabelSettings)
        highlight(NavDestination.HELP, R.id.navIconHelp, R.id.navLabelHelp)
        setThemeBall()

        activity.findViewById<View>(R.id.navItemDashboard).setOnClickListener {
            drawerLayout.closeDrawers()
            if (current != NavDestination.DASHBOARD) SavedWorkflowsActivity.start(activity)
        }
        activity.findViewById<View>(R.id.navItemThemes).setOnClickListener {
            drawerLayout.closeDrawers()
            if (current != NavDestination.THEMES) ThemesActivity.start(activity)
        }
        activity.findViewById<View>(R.id.navItemSettings).setOnClickListener {
            drawerLayout.closeDrawers()
            Toast.makeText(activity, R.string.nav_settings_unavailable, Toast.LENGTH_SHORT).show()
        }
        activity.findViewById<View>(R.id.navItemHelp).setOnClickListener {
            drawerLayout.closeDrawers()
            Toast.makeText(activity, R.string.nav_help_unavailable, Toast.LENGTH_SHORT).show()
        }

        // Calo is dark-only for now — no light theme exists to switch to.
        // Reverting the toggle (rather than leaving it showing "off" with no
        // effect) keeps the switch honest about what state is actually applied.
        activity.findViewById<Switch>(R.id.navDarkModeSwitch).setOnCheckedChangeListener { button, isChecked ->
            if (!isChecked) {
                Toast.makeText(activity, R.string.nav_dark_mode_unavailable, Toast.LENGTH_SHORT).show()
                button.isChecked = true
            }
        }
    }

    /**
     * Icons are always visible — only their color follows selection, matching
     * the row's label exactly. [iconId] is null for the Themes row: its
     * leading ball depicts the active THEME, not this row's selection state
     * (see setThemeBall), so it's never tinted here.
     */
    private fun highlight(destination: NavDestination, iconId: Int?, labelId: Int) {
        val isCurrent = destination == current
        val color = resolveAttrColor(
            if (isCurrent) R.attr.caloColorTextPrimary else R.attr.caloColorTextSecondary
        )
        activity.findViewById<TextView>(labelId).setTextColor(color)
        iconId?.let {
            activity.findViewById<ImageView>(it).imageTintList = ColorStateList.valueOf(color)
        }
    }

    /** The Themes row's ball always shows the theme that's ACTUALLY active, independent of [current]. */
    private fun setThemeBall() {
        val ballRes = when (ThemePrefs.get(activity)) {
            ThemePrefs.Theme.DEFAULT -> R.drawable.shape_theme_ball_default
            ThemePrefs.Theme.BLACK_WHITE -> R.drawable.shape_theme_ball_blackwhite
        }
        activity.findViewById<View>(R.id.navThemeBall).setBackgroundResource(ballRes)
    }

    private fun resolveAttrColor(attr: Int): Int {
        val typedValue = TypedValue()
        activity.theme.resolveAttribute(attr, typedValue, true)
        return typedValue.data
    }
}
