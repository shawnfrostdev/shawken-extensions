package com.shawken.xprime

import com.shawken.app.plugins.BasePlugin
import com.shawken.app.plugins.ShawkenPlugin

@ShawkenPlugin
class XprimePlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(XprimeProvider())
    }
}
