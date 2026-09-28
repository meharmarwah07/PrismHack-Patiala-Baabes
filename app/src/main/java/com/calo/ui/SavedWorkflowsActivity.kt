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
 */
class SavedWorkflowsActivity : CaloBaseActivity() {

    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.Main + job)
    private lateinit var repository: FlowRepository

    private lateinit var drawerLayout: DrawerLayout
    private lateinit var rowsContainer: LinearLayout
    private lateinit var emptyText: TextView
    private lateinit var searchInput: EditText

    private var allFlows: List<LearnedFlow> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_saved_workflows)

        repository = FlowRepository(applicationContext)

        drawerLayout = findViewById(R.id.drawerLayout)
        rowsContainer = findViewById(R.id.workflowRowsContainer)
        emptyText = findViewById(R.id.emptyText)
        searchInput = findViewById(R.id.searchInput)

        NavDrawerController(this, drawerLayout, NavDestination.DASHBOARD).setup()

        findViewById<ImageButton>(R.id.menuButton).setOnClickListener {
            drawerLayout.openDrawer(Gravity.START)
        }

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
                compareByDescending<LearnedFlow> { it.lastUsedAt ?: -1 }.thenByDescending { it.createdAt }
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
            rowsContainer.addView(row)
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
