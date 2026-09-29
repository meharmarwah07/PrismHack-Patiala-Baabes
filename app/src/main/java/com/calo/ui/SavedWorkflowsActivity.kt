package com.calo.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.drawerlayout.widget.DrawerLayout
import com.calo.R
import com.calo.data.FlowRepository
import com.calo.data.LearnedFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Lists every taught LearnedFlow with its real usage stats
 * (FlowRepository.recordUsage, called from CaloOrchestrator on each real
 * voice replay). No RecyclerView dependency in this module, same as
 * dialog_promote_slots' rows — a plain LinearLayout rebuilt on every load
 * is simplest for a list this size.
 *
 * Tapping a row toggles it into [selectedIds] (no separate "selection
 * mode" — rows have no other tap action, so this is unambiguous) and
 * highlights it; the top-right delete icon (same slot the home screen's
 * profile button sits in) deletes whatever's selected, after confirming.
 */
class SavedWorkflowsActivity : CaloBaseActivity() {

    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.Main + job)
    private lateinit var repository: FlowRepository

    private lateinit var rowsContainer: LinearLayout
    private lateinit var emptyText: TextView
    private lateinit var searchInput: EditText
    private lateinit var deleteButton: ImageButton

    private var allFlows: List<LearnedFlow> = emptyList()
    private val selectedIds = mutableSetOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_saved_workflows)

        repository = FlowRepository(applicationContext)

        val drawer = findViewById<DrawerLayout>(R.id.drawerLayout)
        drawerLayout = drawer // base class needs this to close-not-exit on back press
        rowsContainer = findViewById(R.id.workflowRowsContainer)
        emptyText = findViewById(R.id.emptyText)
        searchInput = findViewById(R.id.searchInput)
        deleteButton = findViewById(R.id.deleteButton)

        NavDrawerController(this, drawer, NavDestination.DASHBOARD).setup()

        findViewById<ImageButton>(R.id.menuButton).setOnClickListener {
            drawer.openDrawer(Gravity.START)
        }
        deleteButton.setOnClickListener { onDeleteButtonTapped() }

        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                render(s?.toString().orEmpty())
            }
        })

        loadFlows()
    }

    override fun onResume() {
        super.onResume()
        // Usage stats can change while this screen isn't visible (a voice
        // command replayed from the home screen); refresh so numbers shown
        // here are never stale.
        loadFlows()
    }

    private fun loadFlows() {
        scope.launch {
            allFlows = repository.all().sortedWith(
                // ?: -1 (Int) here previously crashed with ClassCastException:
                // Kotlin infers the selector's type from BOTH branches, and a
                // bare -1 is Int while lastUsedAt is Long? — compareByDescending
                // then mixes boxed Long and boxed Integer at runtime, which
                // compareValues can't compare. -1L keeps the whole selector Long.
                compareByDescending<LearnedFlow> { it.lastUsedAt ?: -1L }.thenByDescending { it.createdAt }
            )
            render(searchInput.text?.toString().orEmpty())
        }
    }

    private fun render(query: String) {
        val filtered = if (query.isBlank()) {
            allFlows
        } else {
            val needle = query.trim().lowercase(Locale.getDefault())
            allFlows.filter {
                it.description.lowercase(Locale.getDefault()).contains(needle) ||
                    it.triggerUtterance.lowercase(Locale.getDefault()).contains(needle)
            }
        }

        rowsContainer.removeAllViews()
        if (filtered.isEmpty()) {
            emptyText.visibility = View.VISIBLE
            emptyText.text = getString(
                if (allFlows.isEmpty()) R.string.saved_workflows_empty else R.string.saved_workflows_empty_search
            )
            return
        }
        emptyText.visibility = View.GONE

        for (flow in filtered) {
            val row = layoutInflater.inflate(R.layout.item_saved_workflow, rowsContainer, false)
            row.findViewById<TextView>(R.id.workflowDescription).text = flow.description
            row.findViewById<TextView>(R.id.workflowUsed).text = if (flow.usageCount > 0) {
                getString(R.string.saved_workflow_used, flow.usageCount)
            } else {
                getString(R.string.saved_workflow_used_never)
            }
            row.findViewById<TextView>(R.id.workflowLastUsed).text = flow.lastUsedAt?.let {
                getString(R.string.saved_workflow_last_used, formatLastUsed(it))
            } ?: getString(R.string.saved_workflow_last_used_never)
            applySelectedBackground(row, flow.id in selectedIds)
            row.setOnClickListener { toggleSelection(flow.id, row) }
            rowsContainer.addView(row)
        }
    }

    // ---- Delete-by-selection ---------------------------------------------

    private fun toggleSelection(flowId: String, row: View) {
        val nowSelected = if (selectedIds.remove(flowId)) false else { selectedIds.add(flowId); true }
        applySelectedBackground(row, nowSelected)
        updateDeleteButtonState()
    }

    private fun applySelectedBackground(row: View, selected: Boolean) {
        row.setBackgroundResource(if (selected) R.drawable.bg_workflow_row_selected else R.drawable.bg_workflow_row)
    }

    private fun updateDeleteButtonState() {
        deleteButton.alpha = if (selectedIds.isEmpty()) 0.4f else 1f
    }

    private fun onDeleteButtonTapped() {
        if (selectedIds.isEmpty()) {
            Toast.makeText(this, R.string.saved_workflows_delete_none_selected, Toast.LENGTH_SHORT).show()
            return
        }
        val count = selectedIds.size
        AlertDialog.Builder(this)
            .setTitle(R.string.saved_workflows_delete_confirm_title)
            .setMessage(resources.getQuantityString(R.plurals.saved_workflows_delete_confirm_message, count, count))
            .setPositiveButton(R.string.saved_workflows_delete_confirm_action) { _, _ -> deleteSelected() }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    private fun deleteSelected() {
        val toDelete = allFlows.filter { it.id in selectedIds }
        scope.launch {
            toDelete.forEach { repository.delete(it) }
            selectedIds.clear()
            updateDeleteButtonState()
            loadFlows()
        }
    }

    private fun formatLastUsed(timestampMs: Long): String =
        SimpleDateFormat("d MMM", Locale.getDefault()).format(java.util.Date(timestampMs)).lowercase(Locale.getDefault())

    override fun onDestroy() {
        super.onDestroy()
        job.cancel()
    }

    companion object {
        fun start(from: Activity) {
            from.startActivity(Intent(from, SavedWorkflowsActivity::class.java))
        }
    }
}
