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

    @Query("SELECT * FROM device_snapshots WHERE dayKey = :day ORDER BY timestamp DESC")
    fun observeByDay(day: String): Flow<List<DeviceSnapshot>>

    @Query("SELECT * FROM device_snapshots ORDER BY timestamp DESC LIMIT 1")
    fun observeLatest(): Flow<DeviceSnapshot?>

    @Query("SELECT * FROM device_snapshots ORDER BY timestamp DESC LIMIT 1")
    suspend fun latest(): DeviceSnapshot?

    @Query("SELECT * FROM device_snapshots WHERE timestamp < :before ORDER BY timestamp DESC LIMIT 1")
    suspend fun latestBefore(before: Long): DeviceSnapshot?

    @Query("SELECT * FROM device_snapshots WHERE dayKey = :day ORDER BY timestamp ASC")
    suspend fun getByDay(day: String): List<DeviceSnapshot>

    @Query("DELETE FROM device_snapshots WHERE timestamp < :before")
    suspend fun deleteOlderThan(before: Long)
}

@Dao
interface DailySummaryDao {
    @Upsert
    suspend fun upsert(summary: DailySummary)

    @Query("SELECT * FROM daily_summary WHERE dayKey = :day")
    fun observeDay(day: String): Flow<DailySummary?>

    @Query("SELECT * FROM daily_summary ORDER BY dayKey DESC")
    fun observeAll(): Flow<List<DailySummary>>

    @Query("SELECT * FROM daily_summary WHERE dayKey = :day")
    suspend fun getDay(day: String): DailySummary?
}
@Dao
interface AppUsageDao {
    @Upsert
    suspend fun upsertAll(items: List<AppUsage>)

    @Query("SELECT * FROM app_usage WHERE dayKey = :day ORDER BY usageMs DESC")
    fun observeByDay(day: String): Flow<List<AppUsage>>

    @Query("SELECT * FROM app_usage WHERE dayKey = :day ORDER BY usageMs DESC")
    suspend fun getByDay(day: String): List<AppUsage>

    @Query("DELETE FROM app_usage WHERE dayKey = :day")
    suspend fun deleteDay(day: String)

    /**
     * 用 [items] 整体替换某天的记录。
     * 删+写在同一个事务里：中途失败会回滚，不会把当天数据清空。
     */
    @Transaction
    suspend fun replaceDay(day: String, items: List<AppUsage>) {
        deleteDay(day)
        if (items.isNotEmpty()) upsertAll(items)
    }

    @Query("DELETE FROM app_usage WHERE dayKey < :beforeDay")
    suspend fun deleteBeforeDay(beforeDay: String)
}

@Dao
interface AppSessionDao {
    /** 用 REPLACE 保证同一 (startAt, 包名) 不会重复。 */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<AppSession>)

    @Query("SELECT * FROM app_session WHERE dayKey = :day ORDER BY startAt DESC")
    fun observeByDay(day: String): Flow<List<AppSession>>

    @Query("SELECT * FROM app_session WHERE dayKey = :day ORDER BY startAt DESC")
    suspend fun getByDay(day: String): List<AppSession>

    @Query("DELETE FROM app_session WHERE dayKey = :day")
    suspend fun deleteDay(day: String)

    /**
     * 用 [items] 整体替换某天的片段记录。
     * 删+写在同一个事务里，避免半截状态。
     */
    @Transaction
    suspend fun replaceDay(day: String, items: List<AppSession>) {
        deleteDay(day)
        if (items.isNotEmpty()) insertAll(items)
    }

    @Query("DELETE FROM app_session WHERE dayKey < :beforeDay")
    suspend fun deleteBeforeDay(beforeDay: String)
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

    /** 某天是否已存在某个类型的事件（用于「今天第 1 次打开手机」这类判定）。 */
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
