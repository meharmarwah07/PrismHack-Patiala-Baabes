package com.calo.data

import android.content.Context

/**
 * Thin wrapper around FlowDao so callers (TeachRecorder, CaloOrchestrator)
 * never touch Room directly. Also the one seam where we'd add caching if
 * "getFlowsForApp" ever gets called on every accessibility event instead of
 * once per voice trigger.
 */
class FlowRepository(context: Context) {
    private val dao = FlowDatabase.get(context).flowDao()

    suspend fun save(flow: LearnedFlow) = dao.save(flow)

    suspend fun flowsForApp(packageName: String): List<LearnedFlow> =
        dao.getFlowsForApp(packageName)

    suspend fun all(): List<LearnedFlow> = dao.getAll()

    suspend fun delete(flow: LearnedFlow) = dao.delete(flow)

    suspend fun deleteAll() = dao.deleteAll()
}
