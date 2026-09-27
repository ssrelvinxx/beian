package com.beian.tracker.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [TrackPoint::class, DeviceSnapshot::class, DailySummary::class, AppUsage::class, AppSession::class, EventLog::class, ImportedSource::class],
    version = 4,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun trackPointDao(): TrackPointDao

    abstract fun deviceSnapshotDao(): DeviceSnapshotDao

    abstract fun dailySummaryDao(): DailySummaryDao

    abstract fun appUsageDao(): AppUsageDao

    abstract fun appSessionDao(): AppSessionDao

    abstract fun eventLogDao(): EventLogDao

    abstract fun importedSourceDao(): ImportedSourceDao

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