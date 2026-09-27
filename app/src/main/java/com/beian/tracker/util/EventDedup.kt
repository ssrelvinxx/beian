package com.beian.tracker.util

import com.beian.tracker.data.EventLog
import com.beian.tracker.data.EventType

/**
 * 报备界面的**显示层折叠**。
 *
 * 数据库里的事件是「如实记录」的：每次亮屏、熄屏都写一条。
 * 但直接铺在聊天流里会很难看 —— 用户一晚上瞄 20 次时间，
 * 列表就是 40 条「打开屏幕 / 关闭屏幕」，翻都翻不完。
 *
 * 这里把「熄屏后很快又亮屏」的成对事件合并成一条「看了一眼手机」，
 * 只影响显示，不动数据库。
 */
object EventDedup {

    /**
     * 熄屏 → 亮屏 的间隔小于这个值，就认为是「瞄一眼」，
     * 合并成一条，不分开显示。
     */
    const val GLANCE_MAX_MS = 5 * 60_000L

    /**
     * 「瞄一眼」的合并文案。
     *
     * ⚠️ 事件文案目前统一存在数据库里（EventReceiver / StaticEventReceiver 也是
     * 硬编码中文），所以这里保持一致，不单独走 strings.xml。
     * 将来要做多语言的话，得把文案从「存储时生成」改成「渲染时生成」。
     */
    const val GLANCE_TITLE = "看了一眼手机"

    /**
     * 折叠事件列表（输入需按时间**倒序**，与页面一致）。
     *
     * 规则：
     *  1. `SCREEN_ON` 紧跟在一个很短时间前的 `SCREEN_OFF` 之后 →
     *     丢掉那条 `SCREEN_OFF`，并把 `SCREEN_ON` 的文案改成「看了一眼手机」。
     *  2. `FIRST_OPEN_TODAY` 与同一时刻的 `SCREEN_ON` 重复（都是亮屏瞬间产生）→
     *     保留 `FIRST_OPEN_TODAY`（信息更多），丢掉那条普通 `SCREEN_ON`。
     *  3. `UNLOCK` 紧跟 `SCREEN_ON` 且几乎同时 → 丢掉 `UNLOCK`（亮屏通常伴随解锁）。
     */
    fun collapse(events: List<EventLog>): List<EventLog> {
        if (events.size < 2) return events

        val drop = HashSet<String>()
        val rewrite = HashMap<String, String>()

        for (i in events.indices) {
            val cur = events[i]
            val next = events.getOrNull(i + 1) ?: continue   // 倒序：next 是更早的

            when (cur.type) {
                // 规则 1：亮屏 + 刚刚熄过屏 → 合并成「看了一眼手机」
                EventType.SCREEN_ON -> {
                    if (next.type == EventType.SCREEN_OFF &&
                        cur.timestamp - next.timestamp in 0..GLANCE_MAX_MS
                    ) {
                        drop.add(next.id)
                        rewrite[cur.id] = GLANCE_TITLE
                    }
                }

                // 规则 2：亮屏瞬间同时产生的 FIRST_OPEN_TODAY 与 SCREEN_ON
                EventType.FIRST_OPEN_TODAY -> {
                    if (next.type == EventType.SCREEN_ON &&
                        kotlin.math.abs(cur.timestamp - next.timestamp) < 3_000L
                    ) {
                        drop.add(next.id)
                    }
                }

                // 规则 3：亮屏紧接着解锁 → 只留亮屏
                EventType.UNLOCK -> {
                    if (next.type == EventType.SCREEN_ON &&
                        kotlin.math.abs(cur.timestamp - next.timestamp) < 3_000L
                    ) {
                        drop.add(cur.id)
                    }
                }
            }
        }

        return events
            .filterNot { it.id in drop }
            .map { ev ->
                val t = rewrite[ev.id] ?: return@map ev
                ev.copy(title = t)
            }
    }
}
