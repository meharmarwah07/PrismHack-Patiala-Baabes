package com.calo.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

@Database(entities = [LearnedFlow::class], version = 1, exportSchema = false)
@TypeConverters(Converters::class)
abstract class FlowDatabase : RoomDatabase() {
    abstract fun flowDao(): FlowDao

    companion object {
        @Volatile private var INSTANCE: FlowDatabase? = null

        fun get(context: Context): FlowDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    FlowDatabase::class.java,
                    "flow_library.db"
                ).build().also { INSTANCE = it }
            }
    }
}
