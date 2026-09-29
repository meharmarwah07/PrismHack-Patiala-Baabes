package com.calo.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.Gravity
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.drawerlayout.widget.DrawerLayout
import com.calo.R
import com.calo.accessibility.CaloAccessibilityService
import com.calo.domain.model.ActionType
import com.calo.domain.model.FlowStep
import com.calo.domain.model.SlotDefinition
import com.calo.orchestrator.CaloOrchestrator
import com.calo.teach.TeachRecorder

/**
 * The Calo home screen — the marble mock, wired to real behavior:
 *
 *   marble tap      -> RECORD_AUDIO permission check, then
 *                      CaloOrchestrator.startVoiceCommand()
 *   bottom pill      -> toggles teaching mode: CaloOrchestrator.startTeaching()
 *                      to begin; finishing stops teaching itself (see
 *                      beginFinishTeaching below — CaloAccessibilityService
 *                      .stopTeaching() can only be called once per session,
 *                      so this activity owns that call rather than leaving
 *                      it to the orchestrator), then walks the user through
 *                      a slot-review dialog (only if there's anything
 *                      promotable) and the trigger phrase + label dialog
 *                      before calling CaloOrchestrator.saveTaughtFlow()
 *
 * This activity owns its own CaloOrchestrator rather than reaching through
 * CaloAccessibilityService.orchestrator: an orchestrator only needs a
 * Context (see its constructor) and this activity has its own lifecycle to
 * cancel it against in onDestroy, so there's no reason to borrow the
 * service's instance. The service's own orchestrator field remains — it's
 * what DebugTriggerReceiver still drives — this is a second, independent
 * one for the real UI path.
 *
 * Menu opens the nav drawer (NavDrawerController); profile opens
 * SettingsActivity, landing on its Account Details section.
 */
class MainActivity : CaloBaseActivity() {

    private lateinit var orchestrator: CaloOrchestrator
    private lateinit var statusText: TextView
    private lateinit var teachButton: TextView
    private var isTeaching = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        orchestrator = CaloOrchestrator(applicationContext)

        val drawer = findViewById<DrawerLayout>(R.id.drawerLayout)
        drawerLayout = drawer // base class needs this to close-not-exit on back press
        statusText = findViewById(R.id.statusText)
        teachButton = findViewById(R.id.teachButton)

        // No drawer item highlights here: the marble/home screen is reached
        // via the launcher icon, not a drawer item — Dashboard's own row
        // still navigates (to SavedWorkflowsActivity), it just isn't "this".
        NavDrawerController(this, drawer, current = null).setup()

        val marble = findViewById<android.view.View>(R.id.marble)
        marble.setBackgroundResource(
            // Palette only — the marble artwork itself doesn't change between
            // Dark and Light mode (see themes.xml's comment on this).
            when (ThemePrefs.get(this).palette) {
                ThemePrefs.Palette.DEFAULT -> R.drawable.marble_default
                ThemePrefs.Palette.BLACK_WHITE -> R.drawable.marble_blackwhite
            }
        )
        marble.setOnClickListener { onMarbleTapped() }
        teachButton.setOnClickListener { onTeachButtonTapped() }

        findViewById<ImageButton>(R.id.menuButton).setOnClickListener {
            drawer.openDrawer(Gravity.START)
        }
        findViewById<ImageButton>(R.id.profileButton).setOnClickListener {
            SettingsActivity.start(this)
        }
    }

    // ---- Marble: voice command ---------------------------------------

    private fun onMarbleTapped() {
        if (isTeaching) {
            // Teaching captures taps on the app under test, not voice — the
            // marble is the wrong control mid-teach. Say so instead of
            // silently starting a listen that would just interrupt the demo.
            statusText.text = getString(R.string.status_teaching_active)
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            statusText.text = getString(R.string.status_mic_permission_needed)
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_RECORD_AUDIO
            )
            return
        }
        startListening()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_RECORD_AUDIO) return
        if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startListening()
        } else {
            statusText.text = getString(R.string.status_mic_permission_needed)
        }
    }

    private fun startListening() {
        if (CaloAccessibilityService.instance == null) {
            statusText.text = getString(R.string.status_service_disabled)
            return
        }
        statusText.text = getString(R.string.status_listening)
        // onStatus can fire from a coroutine callback; CaloOrchestrator runs
        // its scope on Dispatchers.Main, but runOnUiThread costs nothing and
        // keeps this correct even if that changes.
        orchestrator.startVoiceCommand(
            onStatus = { status -> runOnUiThread { statusText.text = status } },
            onConfirm = { question, answer -> runOnUiThread { showConfirmDialog(question, answer) } }
        )
    }

    /** Low-confidence match: the user decides before anything is tapped. */
    private fun showConfirmDialog(question: String, answer: (Boolean) -> Unit) {
        AlertDialog.Builder(this)
            .setTitle(question)
            .setCancelable(false)
            .setPositiveButton(R.string.dialog_yes) { _, _ -> answer(true) }
            .setNegativeButton(R.string.dialog_no) { _, _ -> answer(false) }
            .show()
    }

    /**
     * The service may have resumed an interrupted teaching session after a
     * process restart (see TeachCheckpoint), or this activity may have been
     * recreated mid-teach: show whatever the service is actually doing.
     */
    override fun onResume() {
        super.onResume()
        val teachingNow = CaloAccessibilityService.instance?.mode == CaloAccessibilityService.Mode.TEACHING
        if (teachingNow != isTeaching) {
            isTeaching = teachingNow
            teachButton.text = getString(
                if (teachingNow) R.string.action_finish_teaching else R.string.action_start_teaching
            )
            if (teachingNow) statusText.text = getString(R.string.status_teaching_active)
        }
    }

    // ---- Bottom pill: teach / finish teaching --------------------------

    private fun onTeachButtonTapped() {
        if (!isTeaching) {
            if (CaloAccessibilityService.instance == null) {
                statusText.text = getString(R.string.status_service_disabled)
                return
            }
            orchestrator.startTeaching()
            isTeaching = true
            teachButton.text = getString(R.string.action_finish_teaching)
            statusText.text = getString(R.string.status_teaching_active)
        } else {
            beginFinishTeaching()
        }
    }

    /**
     * Stops teaching immediately on tap — this is the ONLY stopTeaching()
     * call in the whole finish-teaching flow. stopTeaching() nulls out the
     * service's recorder and returns it, so calling it twice would hand the
     * second caller null; every dialog after this one (slot review, then
     * trigger/description) operates on the TeachRecorder instance captured
     * right here, never asks the service to stop again, and the service is
     * already back in IDLE mode for the rest of this flow.
     */
    private fun beginFinishTeaching() {
        val recorder = CaloAccessibilityService.instance?.stopTeaching()
        if (recorder == null) {
            statusText.text = getString(R.string.status_flow_save_failed)
            resetTeachButton()
            return
        }
        val steps = recorder.currentSteps()
        val targetPackage = recorder.currentTargetPackage()
        if (steps.isEmpty() || targetPackage.isNullOrBlank()) {
            statusText.text = getString(R.string.status_flow_save_failed)
            resetTeachButton()
            return
        }
        if (!recorder.hasRecordedUserAction()) {
            // Every captured step was incidental (SCROLL/WAIT only) — no
            // CLICK/SET_TEXT for whatever the person actually tapped was
            // ever recorded. See TeachRecorder.hasRecordedUserAction's doc:
            // saving this as-is would silently store the wrong flow.
            statusText.text = getString(R.string.status_flow_no_action_captured)
            resetTeachButton()
            return
        }

        val promotable = steps.mapNotNull { step -> step.promotableLabel()?.let { step to it } }
        if (promotable.isEmpty()) {
            // T1/T2/T8: nothing recorded is nameable (e.g. only clicks on
            // unlabeled icons), so skip straight to save — a flow with zero
            // promoted slots must still work.
            showFinishTeachingDialog(recorder, targetPackage)
        } else {
            showSlotReviewDialog(recorder, targetPackage, promotable)
        }
    }

    /**
     * SET_TEXT's recordedValue, or a CLICK whose target had visible text —
     * anything else has nothing to name. The CLICK branch also falls back to
     * `recordedValue` because TeachRecorder's null-source recovery path
     * (see its class doc) anchors a CLICK to the nearest CLICKABLE ancestor,
     * which is frequently a container with no text of its own — the text
     * that actually matched lives in `recordedValue` instead for exactly
     * this case (a normal, non-recovered CLICK still has its label in
     * `target.text` and `recordedValue` stays null, so this is additive,
     * not a behavior change for the common case).
     */
    private fun FlowStep.promotableLabel(): String? = when {
        action == ActionType.SET_TEXT && !recordedValue.isNullOrBlank() ->
            getString(R.string.step_label_typed, recordedValue)
        action == ActionType.CLICK && !(target.text ?: recordedValue).isNullOrBlank() ->
            getString(R.string.step_label_tapped, target.text ?: recordedValue)
        else -> null
    }

    private fun showSlotReviewDialog(
        recorder: TeachRecorder,
        targetPackage: String,
        promotable: List<Pair<FlowStep, String>>
    ) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_promote_slots, null)
        val rowsContainer = dialogView.findViewById<LinearLayout>(R.id.slotRowsContainer)

        val rowInputs = promotable.map { (step, label) ->
            val row = layoutInflater.inflate(R.layout.dialog_promote_slot_row, rowsContainer, false)
            row.findViewById<TextView>(R.id.slotRowLabel).text = label
            val input = row.findViewById<EditText>(R.id.slotRowNameInput)
            rowsContainer.addView(row)
            step.order to input
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.dialog_promote_slots_title)
            .setView(dialogView)
            .setCancelable(false)
            .setPositiveButton(R.string.dialog_next) { _, _ ->
                // Mutates recorder in place — promoteToSlot() is a no-op for
                // any stepOrder that doesn't exist, so this is safe to call
                // even for rows the user left blank (it just isn't called).
                for ((stepOrder, input) in rowInputs) {
                    val slotName = input.text?.toString()?.trim().orEmpty()
                    if (slotName.isNotEmpty()) {
                        recorder.promoteToSlot(stepOrder, slotName)
                    }
                }
                showFinishTeachingDialog(recorder, targetPackage)
            }
            .setNegativeButton(R.string.dialog_cancel) { _, _ ->
                // Teaching was already stopped in beginFinishTeaching(); no
                // promotions were applied (recorder is discarded here), so
                // there's nothing to save and nothing left to undo.
                resetTeachButton()
            }
            .show()
    }

    private fun showFinishTeachingDialog(recorder: TeachRecorder, targetPackage: String) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_finish_teaching, null)
        val triggerInput = dialogView.findViewById<EditText>(R.id.triggerUtteranceInput)
        val descriptionInput = dialogView.findViewById<EditText>(R.id.descriptionInput)

        AlertDialog.Builder(this)
            .setTitle(R.string.dialog_finish_teaching_title)
            .setView(dialogView)
            .setCancelable(false)
            .setPositiveButton(R.string.dialog_save) { _, _ ->
                val trigger = triggerInput.text?.toString()?.trim().orEmpty()
                val description = descriptionInput.text?.toString()?.trim().orEmpty()
                if (trigger.isEmpty() || description.isEmpty()) {
                    // Teaching is already stopped (beginFinishTeaching did
                    // that up front) — nothing to save, nothing else to undo.
                    statusText.text = getString(R.string.dialog_fields_required)
                } else {
                    val finalSteps = recorder.currentSteps()
                    val slots = finalSteps.mapNotNull { step ->
                        step.slotName?.let { name ->
                            SlotDefinition(
                                name = name,
                                exampleValue = step.recordedValue ?: step.target.text.orEmpty(),
                                producedByStepIndex = step.order
                            )
                        }
                    }
                    orchestrator.saveTaughtFlow(finalSteps, targetPackage, slots, trigger, description) { success ->
                        runOnUiThread {
                            statusText.text = getString(
                                if (success) R.string.status_flow_saved
                                else R.string.status_flow_save_failed
                            )
                        }
                    }
                }
                resetTeachButton()
            }
            .setNegativeButton(R.string.dialog_cancel) { _, _ ->
                resetTeachButton()
            }
            .show()
    }

    private fun resetTeachButton() {
        isTeaching = false
        teachButton.text = getString(R.string.action_start_teaching)
    }

    override fun onDestroy() {
        super.onDestroy()
        orchestrator.shutdown()
    }

    companion object {
        private const val REQUEST_RECORD_AUDIO = 1001
    }
}
