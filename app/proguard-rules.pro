# ── osmdroid ────────────────────────────────────────────────────────────────
#
# 整个包 keep 住。
#
# 原本是为了兜住它内部可能的反射（瓦片源的类名会被写进配置、
# 以及它从 XML 里 inflate 气泡布局）。开了 R8 之后若裁掉这些，
# 表现会是「地图能显示但一碰就崩」这种很难查的问题。
#
# 代价很小：osmdroid 本身不大，而真正占体积的是 Compose 那几 MB。
-keep class org.osmdroid.** { *; }
-dontwarn org.osmdroid.**

# ── 已删除的规则 ────────────────────────────────────────────────────────────
#
# 原来还有一条 `-keep class com.google.android.gms.** { *; }`，已删：
#
#   项目**不使用 GMS**。定位走系统原生 android.location.LocationManager
#   （原因见 app/build.gradle.kts 里那段注释：国行 ROM 上 GMS 常缺失，
#   FusedLocationProviderClient 会「调用成功但永不回调」）。
#
#   全项目搜 com.google.android.gms 只剩一处**字符串比较**，
#   在 AppEventDeriver 里用来识别「这是 GMS 相关进程」，不是引用类。
#   所以这条 keep 规则从没匹配到任何类，留着只会让人误以为项目依赖 GMS。

# ── 依赖来源（R8 会保留这些库自己的规则，这里不重复写）────────────────────
#
# · Room：由 room-compiler 生成的代码 + androidx 自带 consumer rules 处理
# · Compose：自带 consumer rules
# · kotlinx.serialization：本项目**未使用**（依赖已移除）
#
# ⚠️ material-icons-extended 不需要在这里 keep。
#    它的每个图标都是独立的顶层对象属性，R8 能精确裁掉未引用的那些 ——
#    这正是我们要的：只保留实际用到的那几个图标。
