package com.beian.tracker.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.RoomDatabase.JournalMode
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [TrackPoint::class, DeviceSnapshot::class, DailySummary::class, AppUsage::class, AppSession::class, EventLog::class, ImportedSource::class],
    version = 5,
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
        /**
         * v4 → v5：device_snapshots 新增 sourceId。
         *
         * 这张表原本没有来源字段，导入的对方数据没法落库，
         * 报备页切到对方后顶部看不到电量 / 网络。
         *
         * 用 ALTER TABLE 加列而不是重建表 —— 老数据是本机采集的历史，
         * 重建会丢，代价太大。已有行补 LOCAL（它们确实都来自本机）。
         */
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE device_snapshots " +
                        "ADD COLUMN sourceId TEXT NOT NULL DEFAULT '$LOCAL_SOURCE'",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS " +
                        "index_device_snapshots_sourceId ON device_snapshots (sourceId)",
                )
            }
        }

        @Volatile
        private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "beian.db",
                ).addMigrations(MIGRATION_4_5)
                    // ── WAL：卡顿的关键 ──────────────────────────────────────
                    //
                    // 默认日志模式（TRUNCATE）下，读事务和写事务互斥：
                    // 采集服务每 60 秒做一次 replaceDay（DELETE 当天全部 + 重插，
                    // 两张表两个事务），这期间 UI 的任何查询（历史页读片段、
                    // 报备页读事件）都得排队等锁 —— 表现就是切页面卡顿。
                    //
                    // WAL 让读和写可以并发：写只改 .wal 文件，读走自己的快照，
                    // 互不阻塞。这是采集 + 界面同进程读写场景的标准做法。
                    //
                    // synchronous=NORMAL 是 WAL 下的推荐值：崩溃不丢已提交事务
                    // （断电才可能丢最后几条），换取明显的写入性能提升。
                    // 采集数据属于「丢了不影响正确性」，这个取舍是合适的。
                    .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                    // 兜底：将来再加字段忘了写迁移时，宁可清库也不要崩溃。
                    // 新增迁移后请优先补显式 Migration，别依赖这条。
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { instance = it }
            }
    }
}