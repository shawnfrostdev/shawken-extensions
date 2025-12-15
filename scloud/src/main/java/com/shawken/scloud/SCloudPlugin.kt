package com.shawken.scloud

import com.shawken.app.plugins.BasePlugin
import com.shawken.app.plugins.ShawkenPlugin

@ShawkenPlugin
class SCloudPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(SCloudProvider())
    }
}
