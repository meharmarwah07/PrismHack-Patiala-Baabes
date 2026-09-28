package com.calo.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [LearnedFlow::class], version = 2, exportSchema = false)
@TypeConverters(Converters::class)
abstract class FlowDatabase : RoomDatabase() {
    abstract fun flowDao(): FlowDao

    companion object {
        @Volatile private var INSTANCE: FlowDatabase? = null

        // Adds Saved Workflows' usage tracking (29 Sep 2026) without wiping
        // whatever a demo has already taught — DEFAULT keeps existing rows
        // valid (usageCount = 0, lastUsedAt = never, matching a freshly
        // taught flow's own defaults in LearnedFlow).
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE learned_flows ADD COLUMN usageCount INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE learned_flows ADD COLUMN lastUsedAt INTEGER")
            }
        }

        fun get(context: Context): FlowDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    FlowDatabase::class.java,
                    "flow_library.db"
                ).addMigrations(MIGRATION_1_2).build().also { INSTANCE = it }
            }
    }
}
