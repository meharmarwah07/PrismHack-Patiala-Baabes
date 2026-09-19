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

    @Delete
    suspend fun delete(flow: LearnedFlow)
}
