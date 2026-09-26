package com.beian.tracker.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [TrackPoint::class, DeviceSnapshot::class, DailySummary::class],
    version = 1,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun trackPointDao(): TrackPointDao

    abstract fun deviceSnapshotDao(): DeviceSnapshotDao

    abstract fun dailySummaryDao(): DailySummaryDao

    companion object {
        @Volatile
        private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "beian.db",
                ).fallbackToDestructiveMigration()
                    .build()
                    .also { instance = it }
            }
    }
}