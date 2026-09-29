package com.calo.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface FlowDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(flow: LearnedFlow)

    // Everything the NLU matcher should consider for the app currently on screen.
    @Query("SELECT * FROM learned_flows WHERE targetPackage = :packageName")
    suspend fun getFlowsForApp(packageName: String): List<LearnedFlow>

    // Everything — for a "what have I learned" screen, or T14-style reporting.
    @Query("SELECT * FROM learned_flows")
    suspend fun getAll(): List<LearnedFlow>

    // Saved Workflows screen's "used : N" / "last used : ..." — bumped once
    // per real replay attempt (see CaloOrchestrator.handleUtterance).
    @Query("UPDATE learned_flows SET usageCount = usageCount + 1, lastUsedAt = :timestamp WHERE id = :id")
    suspend fun recordUsage(id: String, timestamp: Long)

    @Delete
    suspend fun delete(flow: LearnedFlow)

    // DEBUG_RESET tooling (2026-09-26): wipes every saved flow, for a clean
    // re-teach without stale/junk data from earlier debugging sessions.
    // (See DebugTriggerReceiver / DebugResetReceiver. Add a matching DELETE
    // here for every table this @Database gains in future.)
    @Query("DELETE FROM learned_flows")
    suspend fun deleteAll()
}
