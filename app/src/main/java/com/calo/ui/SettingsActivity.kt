package com.calo.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.EditText
import android.widget.ImageButton
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.drawerlayout.widget.DrawerLayout
import com.calo.R
import com.calo.accessibility.CaloAccessibilityService

/**
 * Account Details (a local-only display name — Calo has no login/backend to
 * attach a real account to) and Permissions (the two Calo actually needs:
 * Accessibility Service and Microphone). Each permission row is a status
 * readout, not a local toggle — Android doesn't let an app grant/revoke
 * either one programmatically past the initial request, so tapping a row
 * either requests it (Microphone, if not yet granted) or hands off to the
 * real system settings screen, and onResume() re-reads the true state so
 * the switch never lies about what's actually granted.
 */
class SettingsActivity : CaloBaseActivity() {

    private lateinit var accessibilitySwitch: Switch
    private lateinit var microphoneSwitch: Switch
    private lateinit var accountNameText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        val drawer = findViewById<DrawerLayout>(R.id.drawerLayout)
        drawerLayout = drawer // base class needs this to close-not-exit on back press
        NavDrawerController(this, drawer, NavDestination.SETTINGS).setup()

        findViewById<ImageButton>(R.id.menuButton).setOnClickListener {
            drawer.openDrawer(Gravity.START)
        }

        accountNameText = findViewById(R.id.accountNameText)
        renderAccountName()
        findViewById<android.view.View>(R.id.accountNameRow).setOnClickListener {
            showEditNameDialog()
        }

        accessibilitySwitch = findViewById(R.id.accessibilitySwitch)
        microphoneSwitch = findViewById(R.id.microphoneSwitch)

        findViewById<android.view.View>(R.id.accessibilityRow).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        findViewById<android.view.View>(R.id.microphoneRow).setOnClickListener {
            onMicrophoneRowTapped()
        }
    }

    override fun onResume() {
        super.onResume()
        // Whichever system screen a row sent the user to, they're back here
        // now — re-read the real state rather than trusting whatever this
        // Activity last knew.
        accessibilitySwitch.isChecked = isAccessibilityServiceEnabled(this)
        microphoneSwitch.isChecked = hasMicrophonePermission()
    }

    // ---- Account Details (local-only, no backend) -------------------------

    private fun renderAccountName() {
        val name = accountPrefs().getString(KEY_ACCOUNT_NAME, null)
        if (name.isNullOrBlank()) {
            accountNameText.text = getString(R.string.settings_account_name_placeholder)
        } else {
            accountNameText.text = name
        }
    }

    private fun showEditNameDialog() {
        val input = EditText(this).apply {
            hint = getString(R.string.settings_account_name_hint)
            setText(accountPrefs().getString(KEY_ACCOUNT_NAME, null))
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.settings_account_name_dialog_title)
            .setView(input)
            .setPositiveButton(R.string.dialog_save) { _, _ ->
                accountPrefs().edit().putString(KEY_ACCOUNT_NAME, input.text?.toString()?.trim()).apply()
                renderAccountName()
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    private fun accountPrefs() = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // ---- Permissions --------------------------------------------------

    /**
     * Three real states, not two: granted, permanently denied ("Don't ask
     * again" or an OEM auto-deny — Android then silently no-ops
     * requestPermissions(), no dialog, no callback, nothing the user can
     * see), and genuinely not-yet-asked. Only the third one can actually
     * show the system dialog; the other two need the App Info screen
     * instead, so this tracks "did Calo already ask once" itself —
     * shouldShowRequestPermissionRationale() alone can't distinguish
     * "never asked" from "permanently denied", both return false.
     */
    private fun onMicrophoneRowTapped() {
        when {
            hasMicrophonePermission() -> {
                Toast.makeText(this, R.string.settings_permission_already_granted, Toast.LENGTH_SHORT).show()
                openAppInfoSettings()
            }
            ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.RECORD_AUDIO) ||
                !accountPrefs().getBoolean(KEY_MIC_REQUESTED_BEFORE, false) -> {
                accountPrefs().edit().putBoolean(KEY_MIC_REQUESTED_BEFORE, true).apply()
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_RECORD_AUDIO)
            }
            else -> {
                Toast.makeText(this, R.string.settings_permission_denied_permanently, Toast.LENGTH_SHORT).show()
                openAppInfoSettings()
            }
        }
    }

    private fun openAppInfoSettings() {
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_RECORD_AUDIO) {
            microphoneSwitch.isChecked = hasMicrophonePermission()
        }
    }

    private fun hasMicrophonePermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    companion object {
        private const val PREFS_NAME = "calo_profile_prefs"
        private const val KEY_ACCOUNT_NAME = "display_name"
        private const val KEY_MIC_REQUESTED_BEFORE = "mic_requested_before"
        private const val REQUEST_RECORD_AUDIO = 2001

        fun start(from: Activity) {
            from.startActivity(Intent(from, SettingsActivity::class.java))
        }

        /**
         * CaloAccessibilityService.instance only tells us it's bound RIGHT
         * NOW in this process — the user could have enabled it in a
         * different app session, or this could be a cold check right after
         * returning from Settings. The system's own enabled-services list
         * is the actual source of truth.
         */
        fun isAccessibilityServiceEnabled(context: Context): Boolean {
            val expected = "${context.packageName}/${CaloAccessibilityService::class.java.name}"
            val enabled = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            return enabled.split(':').any { it.equals(expected, ignoreCase = true) }
        }
    }
}
