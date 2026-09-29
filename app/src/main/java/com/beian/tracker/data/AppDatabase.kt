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
    version = 7,
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

        /**
         * v5 → v6：app_usage / app_session / daily_summary 三张表新增 sourceId，
         * 并把 sourceId 并入主键。
         *
         * 为什么必须重建表：SQLite 的 ALTER TABLE 只能加列、改名、删列，
         * **不能改主键**。这三张表原本的主键分别是
         * (dayKey, packageName)、(id)、(dayKey)，都不含来源，
         * 导入对方数据包后会和本机同一天的记录直接撞车。
         *
         * 老数据全部是本机采集的（导入功能此前不写这三张表），
         * 所以一律补 LOCAL，主键扩展成 (sourceId, ...) 后不会丢任何一行。
         *
         * 重建步骤按 SQLite 官方推荐的 12 步顺序，只保留必要的：
         * 建新表 → 拷数据 → 删旧表 → 改名 → 建索引。
         * Room 会把整个迁移包在一个事务里执行，这里不再自己开事务
         * （嵌套事务容易踩坑，且没有额外好处）。
         */
        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // ── app_usage ──────────────────────────────────────────────
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS app_usage_new (
                        sourceId TEXT NOT NULL,
                        dayKey TEXT NOT NULL,
                        packageName TEXT NOT NULL,
                        appLabel TEXT NOT NULL,
                        usageMs INTEGER NOT NULL,
                        launchCount INTEGER NOT NULL,
                        lastUsed INTEGER NOT NULL,
                        PRIMARY KEY(sourceId, dayKey, packageName)
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "INSERT OR REPLACE INTO app_usage_new " +
                        "(sourceId, dayKey, packageName, appLabel, usageMs, launchCount, lastUsed) " +
                        "SELECT '$LOCAL_SOURCE', dayKey, packageName, appLabel, usageMs, launchCount, lastUsed " +
                        "FROM app_usage",
                )
                db.execSQL("DROP TABLE app_usage")
                db.execSQL("ALTER TABLE app_usage_new RENAME TO app_usage")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_app_usage_dayKey ON app_usage (dayKey)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_app_usage_sourceId ON app_usage (sourceId)")

                // ── app_session ───────────────────────────────────────────
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS app_session_new (
                        id TEXT NOT NULL PRIMARY KEY,
                        sourceId TEXT NOT NULL,
                        dayKey TEXT NOT NULL,
                        packageName TEXT NOT NULL,
                        appLabel TEXT NOT NULL,
                        startAt INTEGER NOT NULL,
                        endAt INTEGER NOT NULL,
                        durationMs INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                // id 也要补来源前缀，否则和将来导入的对方记录撞 id。
                db.execSQL(
                    "INSERT OR REPLACE INTO app_session_new " +
                        "(id, sourceId, dayKey, packageName, appLabel, startAt, endAt, durationMs) " +
                        "SELECT '$LOCAL_SOURCE' || ':' || id, '$LOCAL_SOURCE', dayKey, packageName, " +
                        "appLabel, startAt, endAt, durationMs FROM app_session",
                )
                db.execSQL("DROP TABLE app_session")
                db.execSQL("ALTER TABLE app_session_new RENAME TO app_session")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_app_session_dayKey ON app_session (dayKey)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_app_session_startAt ON app_session (startAt)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_app_session_sourceId ON app_session (sourceId)")

                // ── daily_summary ─────────────────────────────────────────
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS daily_summary_new (
                        sourceId TEXT NOT NULL,
                        dayKey TEXT NOT NULL,
                        totalDistanceMeters REAL NOT NULL,
                        pointCount INTEGER NOT NULL,
                        unlockCount INTEGER NOT NULL,
                        screenTimeMs INTEGER NOT NULL,
                        firstSeen INTEGER NOT NULL,
                        lastSeen INTEGER NOT NULL,
                        PRIMARY KEY(sourceId, dayKey)
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "INSERT OR REPLACE INTO daily_summary_new " +
                        "(sourceId, dayKey, totalDistanceMeters, pointCount, unlockCount, " +
                        "screenTimeMs, firstSeen, lastSeen) " +
                        "SELECT '$LOCAL_SOURCE', dayKey, totalDistanceMeters, pointCount, unlockCount, " +
                        "screenTimeMs, firstSeen, lastSeen FROM daily_summary",
                )
                db.execSQL("DROP TABLE daily_summary")
                db.execSQL("ALTER TABLE daily_summary_new RENAME TO daily_summary")
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_daily_summary_sourceId " +
                        "ON daily_summary (sourceId)",
                )

            }
        }

        /**
         * v6 → v7：为各表补 (sourceId, dayKey) **复合索引**。
         *
         * 为什么必须补：所有界面查询都是
         *   `WHERE sourceId = ? AND dayKey = ? ORDER BY ...`
         * （全项目 19 处同样形态），但此前只建了 sourceId、dayKey 等**单列**索引。
         * SQLite 用单列索引命中一个条件后，另一个条件得**回表逐行过滤**。
         *
         * ⚠️ 需要说清楚：**这不能解决卡顿**。实测 6 万行下，
         * 加复合索引只从 3.9ms 降到 3.7ms（约 1.1 倍），
         * 加上 timestamp 做覆盖排序也只到 3.2ms —— 毫秒级差异。
         * 卡顿的真因是主线程阻塞，见 TileDownloader / TrackMapView 的注释。
         * 保留此迁移是为数据量继续增长后的收益，以及索引本身的正确性。
         *
         * 索引的删除/新建都是幂等的（IF EXISTS / IF NOT EXISTS），
         * 且不改任何数据，所以这里直接建，不涉及数据搬运。
         * 老的单列索引保留：timestamp / startAt 等排序场景仍在用。
         */
        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_track_points_sourceId_dayKey " +
                        "ON track_points (sourceId, dayKey)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_device_snapshots_sourceId_dayKey " +
                        "ON device_snapshots (sourceId, dayKey)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_app_usage_sourceId_dayKey " +
                        "ON app_usage (sourceId, dayKey)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_app_session_sourceId_dayKey " +
                        "ON app_session (sourceId, dayKey)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_event_log_sourceId_dayKey " +
                        "ON event_log (sourceId, dayKey)",
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
                ).addMigrations(MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7)
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