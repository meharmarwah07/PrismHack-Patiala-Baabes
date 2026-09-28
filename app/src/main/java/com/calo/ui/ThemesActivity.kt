package com.calo.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.ImageButton
import android.widget.TextView
import androidx.drawerlayout.widget.DrawerLayout
import com.calo.R

/**
 * Picks between the two concrete themes (ThemePrefs.Theme) and applies the
 * choice app-wide. Tapping a card writes the preference immediately and
 * recreate()s this Activity so the picker itself re-skins too — every other
 * open Activity picks it up on its next onResume (see CaloBaseActivity).
 */
class ThemesActivity : CaloBaseActivity() {

    private lateinit var drawerLayout: DrawerLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_themes)

        drawerLayout = findViewById(R.id.drawerLayout)
        NavDrawerController(this, drawerLayout, NavDestination.THEMES).setup()

        findViewById<ImageButton>(R.id.menuButton).setOnClickListener {
            drawerLayout.openDrawer(Gravity.START)
        }

        val current = ThemePrefs.get(this)
        renderSelection(current)

        findViewById<View>(R.id.themeCardDefault).setOnClickListener {
            selectTheme(ThemePrefs.Theme.DEFAULT)
        }
        findViewById<View>(R.id.themeCardBlackWhite).setOnClickListener {
            selectTheme(ThemePrefs.Theme.BLACK_WHITE)
        }
    }

    private fun selectTheme(theme: ThemePrefs.Theme) {
        if (theme == ThemePrefs.get(this)) return
        ThemePrefs.set(this, theme)
        recreate()
    }

    private fun renderSelection(current: ThemePrefs.Theme) {
        val defaultSelected = current == ThemePrefs.Theme.DEFAULT
        findViewById<View>(R.id.themeCardDefault).setBackgroundResource(
            if (defaultSelected) R.drawable.bg_theme_card_selected else R.drawable.bg_theme_card_unselected
        )
        findViewById<View>(R.id.themeCardBlackWhite).setBackgroundResource(
            if (defaultSelected) R.drawable.bg_theme_card_unselected else R.drawable.bg_theme_card_selected
        )
        findViewById<TextView>(R.id.themeLabelDefault).setTextColor(
            resolveAttrColor(if (defaultSelected) R.attr.caloColorTextPrimary else R.attr.caloColorTextSecondary)
        )
        findViewById<TextView>(R.id.themeLabelBlackWhite).setTextColor(
            resolveAttrColor(if (defaultSelected) R.attr.caloColorTextSecondary else R.attr.caloColorTextPrimary)
        )
    }

    private fun resolveAttrColor(attr: Int): Int {
        val typedValue = android.util.TypedValue()
        theme.resolveAttribute(attr, typedValue, true)
        return typedValue.data
    }

    companion object {
        fun start(from: Activity) {
            from.startActivity(Intent(from, ThemesActivity::class.java))
        }
    }
}
