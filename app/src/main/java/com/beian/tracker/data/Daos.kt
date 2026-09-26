package com.beian.tracker.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface TrackPointDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(point: TrackPoint): Long

    @Query("SELECT * FROM track_points WHERE dayKey = :day ORDER BY timestamp ASC")
    fun observeByDay(day: String): Flow<List<TrackPoint>>

    @Query("SELECT * FROM track_points WHERE dayKey = :day ORDER BY timestamp ASC")
    suspend fun getByDay(day: String): List<TrackPoint>

    @Query("SELECT * FROM track_points ORDER BY timestamp DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<TrackPoint>>

    @Query("SELECT MAX(timestamp) FROM track_points")
    suspend fun latestTimestamp(): Long?

    @Query("SELECT COUNT(*) FROM track_points WHERE dayKey = :day")
    suspend fun countByDay(day: String): Int

    @Query("SELECT DISTINCT dayKey FROM track_points ORDER BY dayKey DESC")
    fun observeDays(): Flow<List<String>>

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