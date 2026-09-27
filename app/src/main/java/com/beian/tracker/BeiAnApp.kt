package com.beian.tracker

import android.app.Application
import com.beian.tracker.util.MapTileStore

class BeiAnApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // 把地图瓦片缓存指向持久目录：系统清理缓存不会删掉离线地图
        MapTileStore.configure(this)
    }
}
