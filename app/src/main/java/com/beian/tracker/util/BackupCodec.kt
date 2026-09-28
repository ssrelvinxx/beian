package com.beian.tracker.util

import com.beian.tracker.data.AppSession
import com.beian.tracker.data.AppUsage
import com.beian.tracker.data.DeviceSnapshot
import com.beian.tracker.data.EventLog
import com.beian.tracker.data.TrackPoint
import org.json.JSONArray
import org.json.JSONObject

/**
 * 报备数据包编解码。
 *
 * 导出端把本机数据序列化成 JSON 文本，用户通过任意方式发给对方；
 * 导入端解析后写入数据库，sourceId 用文件里的值，与本机数据隔离。
 */
object BackupCodec {

    const val MAGIC = "beian-backup"
    const val VERSION = 1

    /** 一个完整的数据包。 */
    data class Bundle(
        val sourceId: String,
        /** 采集端自己填的昵称，可为空。 */
        val nickname: String,
        val exportedAt: Long,
        val points: List<TrackPoint>,
        val snapshots: List<DeviceSnapshot>,
        val events: List<EventLog>,
        val appUsage: List<AppUsage>,
        val appSessions: List<AppSession>,
    )

    // ── 编码 ────────────────────────────────────────────────────────────────

    fun encode(bundle: Bundle): String {
        val root = JSONObject()
        root.put("magic", MAGIC)
        root.put("version", VERSION)
        root.put("sourceId", bundle.sourceId)
        root.put("nickname", bundle.nickname)
        root.put("exportedAt", bundle.exportedAt)

        root.put("points", JSONArray().apply {
            bundle.points.forEach { p ->
                put(JSONObject().apply {
                    put("t", p.timestamp)
                    put("d", p.dayKey)
                    put("lat", p.latitude)
                    put("lon", p.longitude)
                    put("alt", p.altitude)
                    put("spd", p.speed.toDouble())
                    put("acc", p.accuracy.toDouble())
                    put("prv", p.provider)
                })
            }
        })

        root.put("events", JSONArray().apply {
            bundle.events.forEach { e ->
                put(JSONObject().apply {
                    put("t", e.timestamp)
                    put("d", e.dayKey)
                    put("ty", e.type)
                    put("ti", e.title)
                    put("de", e.detail)
                    put("v", e.value)
                })
            }
        })

        root.put("appUsage", JSONArray().apply {
            bundle.appUsage.forEach { a ->
                put(JSONObject().apply {
                    put("d", a.dayKey)
                    put("pkg", a.packageName)
                    put("label", a.appLabel)
                    put("ms", a.usageMs)
                    put("n", a.launchCount)
                    put("last", a.lastUsed)
                })
            }
        })

        root.put("appSessions", JSONArray().apply {
            bundle.appSessions.forEach { a ->
                put(JSONObject().apply {
                    put("d", a.dayKey)
                    put("pkg", a.packageName)
                    put("label", a.appLabel)
                    put("s", a.startAt)
                    put("e", a.endAt)
                    put("ms", a.durationMs)
                })
            }
        })

        root.put("snapshots", JSONArray().apply {
            bundle.snapshots.forEach { s ->
                put(JSONObject().apply {
                    put("t", s.timestamp)
                    put("d", s.dayKey)
                    put("bat", s.batteryLevel)
                    put("chg", s.batteryCharging)
                    put("scr", s.screenTimeMs)
                    put("ul", s.unlockCount)
                    put("so", s.screenOnCount)
                    put("nt", s.networkType)
                    put("nc", s.networkConnected)
                    put("nn", s.networkName)
                })
            }
        })

        return root.toString()
    }

    // ── 解码 ────────────────────────────────────────────────────────────────

    /** 解析失败时抛 IllegalArgumentException，附中文原因。 */
    fun decode(text: String, sourceIdOverride: String? = null): Bundle {
        val root = try {
            JSONObject(text)
        } catch (e: Exception) {
            throw IllegalArgumentException("文件不是有效的报备数据包")
        }

        if (root.optString("magic") != MAGIC) {
            throw IllegalArgumentException("文件不是报备数据包")
        }
        val version = root.optInt("version", 0)
        if (version > VERSION) {
            throw IllegalArgumentException("数据包版本过新（v$version），请升级 App")
        }

        // sourceId：优先用调用方指定的，否则用文件内的
        val sourceId = sourceIdOverride
            ?: root.optString("sourceId").takeIf { it.isNotBlank() }
            ?: "IMPORT_${System.currentTimeMillis()}"

        val points = ArrayList<TrackPoint>()
        root.optJSONArray("points")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                points.add(
                    TrackPoint(
                        timestamp = o.optLong("t"),
                        dayKey = o.optString("d"),
                        sourceId = sourceId,
                        latitude = o.optDouble("lat"),
                        longitude = o.optDouble("lon"),
                        altitude = o.optDouble("alt"),
                        speed = o.optDouble("spd").toFloat(),
                        accuracy = o.optDouble("acc").toFloat(),
                        provider = o.optString("prv"),
                    ),
                )
            }
        }

        val events = ArrayList<EventLog>()
        root.optJSONArray("events")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val type = o.optString("ty")
                val ts = o.optLong("t")
                events.add(
                    EventLog(
                        // 导入后 id 带上 sourceId，避免不同来源同秒事件互相覆盖
                        id = "$sourceId:$type:$ts",
                        sourceId = sourceId,
                        dayKey = o.optString("d"),
                        type = type,
                        timestamp = ts,
                        title = o.optString("ti"),
                        detail = o.optString("de"),
                        value = o.optLong("v", -1L),
                    ),
                )
            }
        }

        val appUsage = ArrayList<AppUsage>()
        root.optJSONArray("appUsage")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                appUsage.add(
                    AppUsage(
                        dayKey = o.optString("d"),
                        packageName = o.optString("pkg"),
                        appLabel = o.optString("label"),
                        usageMs = o.optLong("ms"),
                        launchCount = o.optInt("n"),
                        lastUsed = o.optLong("last"),
                    ),
                )
            }
        }

        val appSessions = ArrayList<AppSession>()
        root.optJSONArray("appSessions")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val s = o.optLong("s")
                val pkg = o.optString("pkg")
                appSessions.add(
                    AppSession(
                        id = "$sourceId:$s:$pkg",
                        dayKey = o.optString("d"),
                        packageName = pkg,
                        appLabel = o.optString("label"),
                        startAt = s,
                        endAt = o.optLong("e"),
                        durationMs = o.optLong("ms"),
                    ),
                )
            }
        }

        val snapshots = ArrayList<DeviceSnapshot>()
        root.optJSONArray("snapshots")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                snapshots.add(
                    DeviceSnapshot(
                        timestamp = o.optLong("t"),
                        dayKey = o.optString("d"),
                        // 必须用解析后的 sourceId（含 override），
                        // 用默认值的话对方快照会带着 LOCAL 混进本机视图。
                        sourceId = sourceId,
                        batteryLevel = o.optInt("bat"),
                        batteryCharging = o.optBoolean("chg"),
                        screenTimeMs = o.optLong("scr"),
                        unlockCount = o.optInt("ul"),
                        screenOnCount = o.optInt("so"),
                        networkType = o.optString("nt"),
                        networkConnected = o.optBoolean("nc"),
                        networkName = o.optString("nn"),
                    ),
                )
            }
        }

        return Bundle(
            sourceId = sourceId,
            nickname = root.optString("nickname"),
            exportedAt = root.optLong("exportedAt"),
            points = points,
            snapshots = snapshots,
            events = events,
            appUsage = appUsage,
            appSessions = appSessions,
        )
    }
}
