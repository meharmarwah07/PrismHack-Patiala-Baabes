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
 * Picks between the two Palettes (ThemePrefs.Palette) and applies the
 * choice app-wide — independent of Mode (Dark/Light), which the drawer's
 * own Dark Mode switch controls (see NavDrawerController). Tapping a card
 * writes the preference immediately and recreate()s this Activity so the
 * picker itself re-skins too — every other open Activity picks it up on
 * its next onResume (see CaloBaseActivity).
 */
class ThemesActivity : CaloBaseActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_themes)

        val drawer = findViewById<DrawerLayout>(R.id.drawerLayout)
        drawerLayout = drawer // base class needs this to close-not-exit on back press
        NavDrawerController(this, drawer, NavDestination.THEMES).setup()

        findViewById<ImageButton>(R.id.menuButton).setOnClickListener {
            drawer.openDrawer(Gravity.START)
        }

        renderSelection(ThemePrefs.get(this).palette)

        findViewById<View>(R.id.themeCardDefault).setOnClickListener {
            selectPalette(ThemePrefs.Palette.DEFAULT)
        }
        findViewById<View>(R.id.themeCardBlackWhite).setOnClickListener {
            selectPalette(ThemePrefs.Palette.BLACK_WHITE)
        }
    }

    private fun selectPalette(palette: ThemePrefs.Palette) {
        if (palette == ThemePrefs.get(this).palette) return
        ThemePrefs.setPalette(this, palette)
        recreate()
    }

    private fun renderSelection(current: ThemePrefs.Palette) {
        val defaultSelected = current == ThemePrefs.Palette.DEFAULT
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
