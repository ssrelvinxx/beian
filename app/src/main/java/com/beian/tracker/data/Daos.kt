package com.beian.tracker.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface TrackPointDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(point: TrackPoint): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(points: List<TrackPoint>)

    @Query("SELECT * FROM track_points WHERE sourceId = :sourceId AND dayKey = :day ORDER BY timestamp ASC")
    fun observeByDay(sourceId: String, day: String): Flow<List<TrackPoint>>

    @Query("SELECT * FROM track_points WHERE sourceId = :sourceId AND dayKey = :day ORDER BY timestamp ASC")
    suspend fun getByDay(sourceId: String, day: String): List<TrackPoint>

    @Query("SELECT * FROM track_points WHERE sourceId = :sourceId ORDER BY timestamp ASC")
    suspend fun getAll(sourceId: String): List<TrackPoint>

    @Query("SELECT MAX(timestamp) FROM track_points WHERE sourceId = :sourceId")
    suspend fun latestTimestamp(sourceId: String): Long?

    @Query("SELECT COUNT(*) FROM track_points WHERE sourceId = :sourceId AND dayKey = :day")
    suspend fun countByDay(sourceId: String, day: String): Int

    @Query("SELECT DISTINCT dayKey FROM track_points WHERE sourceId = :sourceId ORDER BY dayKey DESC")
    fun observeDays(sourceId: String): Flow<List<String>>

    @Query("SELECT DISTINCT dayKey FROM track_points WHERE sourceId = :sourceId ORDER BY dayKey DESC")
    suspend fun getDays(sourceId: String): List<String>

    @Query("DELETE FROM track_points WHERE sourceId = :sourceId")
    suspend fun deleteSource(sourceId: String)

    @Query("SELECT DISTINCT dayKey FROM track_points WHERE sourceId = :sourceId")
    suspend fun daysOfSource(sourceId: String): List<String>

    @Query("DELETE FROM track_points WHERE timestamp < :before")
    suspend fun deleteOlderThan(before: Long)
}

@Dao
interface DeviceSnapshotDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(snapshot: DeviceSnapshot): Long

    /**
     * 批量插入（导入对方数据包时用）。
     *
     * 用 REPLACE：导入包里带着采集端的自增 id，和本机的会撞。
     * 调用方在入队前已把 id 归零，让本库重新分配。
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<DeviceSnapshot>)

    /** 某个来源最近的一条快照（报备页顶部信息栏按来源显示）。 */
    @Query(
        "SELECT * FROM device_snapshots WHERE sourceId = :sourceId " +
            "ORDER BY timestamp DESC LIMIT 1",
    )
    fun observeLatestOf(sourceId: String): Flow<DeviceSnapshot?>

    @Query("SELECT * FROM device_snapshots WHERE sourceId = :sourceId ORDER BY timestamp DESC LIMIT 1")
    suspend fun latestOf(sourceId: String): DeviceSnapshot?

    @Query("SELECT * FROM device_snapshots WHERE sourceId = :sourceId AND dayKey = :day ORDER BY timestamp DESC")
    fun observeByDayOf(sourceId: String, day: String): Flow<List<DeviceSnapshot>>

    @Query(
        "SELECT * FROM device_snapshots WHERE sourceId = :sourceId AND timestamp < :before " +
            "ORDER BY timestamp DESC LIMIT 1",
    )
    suspend fun latestBeforeOf(sourceId: String, before: Long): DeviceSnapshot?

    @Query("DELETE FROM device_snapshots WHERE sourceId = :sourceId")
    suspend fun deleteSource(sourceId: String)

    @Query("SELECT * FROM device_snapshots WHERE sourceId = :sourceId AND dayKey = :day ORDER BY timestamp ASC")
    suspend fun getByDay(sourceId: String, day: String): List<DeviceSnapshot>

    @Query("DELETE FROM device_snapshots WHERE timestamp < :before")
    suspend fun deleteOlderThan(before: Long)

    @Query("SELECT DISTINCT dayKey FROM device_snapshots WHERE sourceId = :sourceId")
    suspend fun daysOfSource(sourceId: String): List<String>
}

@Dao
interface DailySummaryDao {
    @Upsert
    suspend fun upsert(summary: DailySummary)

    @Query("SELECT * FROM daily_summary WHERE sourceId = :sourceId AND dayKey = :day")
    fun observeDay(sourceId: String, day: String): Flow<DailySummary?>

    @Query("SELECT * FROM daily_summary WHERE sourceId = :sourceId ORDER BY dayKey DESC")
    fun observeAll(sourceId: String): Flow<List<DailySummary>>

    @Query("SELECT * FROM daily_summary WHERE sourceId = :sourceId AND dayKey = :day")
    suspend fun getDay(sourceId: String, day: String): DailySummary?

    @Query("DELETE FROM daily_summary WHERE sourceId = :sourceId")
    suspend fun deleteSource(sourceId: String)
}

@Dao
interface AppUsageDao {
    @Upsert
    suspend fun upsertAll(items: List<AppUsage>)

    @Query("SELECT * FROM app_usage WHERE sourceId = :sourceId AND dayKey = :day ORDER BY usageMs DESC")
    fun observeByDay(sourceId: String, day: String): Flow<List<AppUsage>>

    @Query("SELECT * FROM app_usage WHERE sourceId = :sourceId AND dayKey = :day ORDER BY usageMs DESC")
    suspend fun getByDay(sourceId: String, day: String): List<AppUsage>

    @Query("DELETE FROM app_usage WHERE sourceId = :sourceId AND dayKey = :day")
    suspend fun deleteDay(sourceId: String, day: String)

    /**
     * 用 [items] 整体替换某来源某天的记录。
     * 删+写在同一个事务里：中途失败会回滚，不会把当天数据清空。
     */
    /**
     * 写入当天的 App 使用记录。
     *
     * 主键是 (sourceId, dayKey, packageName)，每行由这三者唯一确定，
     * 所以直接 upsert 即可，不需要先 DELETE 当天全部。
     *
     * ⚠️ 别把这条当成性能修复 —— 实测删+重插 vs 纯 upsert：
     * 100 条时 0.24ms vs 0.27ms，300 条时 0.78ms vs 0.79ms，**没有差别**。
     * 改成 upsert 是为了代码语义更干净（不做无谓的删-插）。
     * 卡顿的真因在主线程阻塞，见 TileDownloader / TrackMapView 的注释。
     */
    @Transaction
    suspend fun replaceDay(sourceId: String, day: String, items: List<AppUsage>) {
        if (items.isNotEmpty()) upsertAll(items)
    }

    @Query("DELETE FROM app_usage WHERE sourceId = :sourceId AND dayKey < :beforeDay")
    suspend fun deleteBeforeDay(sourceId: String, beforeDay: String)

    @Query("DELETE FROM app_usage WHERE sourceId = :sourceId")
    suspend fun deleteSource(sourceId: String)

    @Query("SELECT DISTINCT dayKey FROM app_usage WHERE sourceId = :sourceId")
    suspend fun daysOfSource(sourceId: String): List<String>

    @Query("SELECT DISTINCT dayKey FROM app_usage WHERE sourceId = :sourceId ORDER BY dayKey DESC")
    fun observeDays(sourceId: String): Flow<List<String>>
}

@Dao
interface AppSessionDao {
    /** 用 REPLACE 保证同一 id 不会重复。 */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<AppSession>)

    @Query("SELECT * FROM app_session WHERE sourceId = :sourceId AND dayKey = :day ORDER BY startAt DESC")
    fun observeByDay(sourceId: String, day: String): Flow<List<AppSession>>

    @Query("SELECT * FROM app_session WHERE sourceId = :sourceId AND dayKey = :day ORDER BY startAt DESC")
    suspend fun getByDay(sourceId: String, day: String): List<AppSession>

    @Query("DELETE FROM app_session WHERE sourceId = :sourceId AND dayKey = :day")
    suspend fun deleteDay(sourceId: String, day: String)

    /**
     * 用 [items] 整体替换某来源某天的片段记录。
     * 删+写在同一个事务里，避免半截状态。
     */
    /**
     * 写入当天的 App 前台片段。
     *
     * [AppSession] 的主键是 `id`，而 id 由
     * `"${LOCAL_SOURCE}:${startAt}:${packageName}"` 唯一确定 ——
     * 同一片段每轮算出来的 id 完全一样，包括**正在使用中的那一条**
     * （它的 endAt/durationMs 在变，但 startAt 和 packageName 不变）。
     *
     * 所以可以直接 upsert：已存在的行被覆盖成最新时长，新片段被插入，
     * 不需要「先删光当天再重插」。
     *
     * ⚠️ 同样地，这不是卡顿的修复。实测两种写法耗时几乎一致
     * （100 条 0.24ms vs 0.27ms）。改它只是为了语义干净，
     * 避免每轮无谓地删掉当天全部行。
     */
    @Transaction
    suspend fun replaceDay(sourceId: String, day: String, items: List<AppSession>) {
        if (items.isNotEmpty()) insertAll(items)
    }

    @Query("DELETE FROM app_session WHERE sourceId = :sourceId AND dayKey < :beforeDay")
    suspend fun deleteBeforeDay(sourceId: String, beforeDay: String)

    @Query("DELETE FROM app_session WHERE sourceId = :sourceId")
    suspend fun deleteSource(sourceId: String)

    @Query("SELECT DISTINCT dayKey FROM app_session WHERE sourceId = :sourceId")
    suspend fun daysOfSource(sourceId: String): List<String>

    @Query("SELECT DISTINCT dayKey FROM app_session WHERE sourceId = :sourceId ORDER BY dayKey DESC")
    fun observeDays(sourceId: String): Flow<List<String>>
}

@Dao
interface EventLogDao {
    /** REPLACE：同 id 重复导入只保留一份。 */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<EventLog>)

    @Query("SELECT * FROM event_log WHERE sourceId = :sourceId AND dayKey = :day ORDER BY timestamp DESC")
    fun observeByDay(sourceId: String, day: String): Flow<List<EventLog>>

    @Query("SELECT * FROM event_log WHERE sourceId = :sourceId ORDER BY timestamp DESC LIMIT :limit")
    fun observeRecent(sourceId: String, limit: Int): Flow<List<EventLog>>

    @Query("SELECT * FROM event_log WHERE sourceId = :sourceId AND dayKey = :day ORDER BY timestamp DESC")
    suspend fun getByDay(sourceId: String, day: String): List<EventLog>

    @Query("SELECT * FROM event_log WHERE sourceId = :sourceId ORDER BY timestamp DESC")
    suspend fun getAll(sourceId: String): List<EventLog>

    @Query("SELECT * FROM event_log WHERE sourceId = :sourceId AND type = :type ORDER BY timestamp DESC LIMIT 1")
    suspend fun latestOfType(sourceId: String, type: String): EventLog?

    /**
     * 最近一条网络事件（WiFi / 移动网络 / 断开三种都算）。
     *
     * 网络去重需要跨类型比较：从 WiFi 切到移动网络也是「状态变了」，
     * 得能看到上一条是哪种。只查单一 type 做不到。
     */
    @Query(
        "SELECT * FROM event_log WHERE sourceId = :sourceId AND type IN (:types) " +
            "ORDER BY timestamp DESC LIMIT 1",
    )
    suspend fun latestOfTypes(sourceId: String, types: List<String>): EventLog?

    /** 某天是否已存在某个类型的事件（用于「今天第 1 次点亮屏幕」这类判定）。 */
    @Query("SELECT COUNT(*) FROM event_log WHERE sourceId = :sourceId AND dayKey = :day AND type = :type")
    suspend fun countOfTypeOnDay(sourceId: String, day: String, type: String): Int

    @Query("SELECT * FROM event_log WHERE sourceId = :sourceId ORDER BY timestamp DESC LIMIT 1")
    suspend fun latest(sourceId: String): EventLog?

    @Query("SELECT DISTINCT dayKey FROM event_log WHERE sourceId = :sourceId ORDER BY dayKey DESC")
    fun observeDays(sourceId: String): Flow<List<String>>

    @Query("SELECT COUNT(*) FROM event_log WHERE sourceId = :sourceId")
    suspend fun count(sourceId: String): Int

    @Query("DELETE FROM event_log WHERE sourceId = :sourceId")
    suspend fun deleteSource(sourceId: String)

    @Query("SELECT DISTINCT dayKey FROM event_log WHERE sourceId = :sourceId")
    suspend fun daysOfSource(sourceId: String): List<String>

    @Query("DELETE FROM event_log WHERE sourceId = :sourceId AND dayKey = :day")
    suspend fun deleteDay(sourceId: String, day: String)
}

@Dao
interface ImportedSourceDao {
    @Upsert
    suspend fun upsert(source: ImportedSource)

    @Query("SELECT * FROM imported_source ORDER BY importedAt DESC")
    fun observeAll(): Flow<List<ImportedSource>>

    @Query("SELECT * FROM imported_source WHERE sourceId = :sourceId")
    suspend fun get(sourceId: String): ImportedSource?

    @Query("DELETE FROM imported_source WHERE sourceId = :sourceId")
    suspend fun delete(sourceId: String)
}
