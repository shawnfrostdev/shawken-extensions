package com.shawken.uhdmovies

import com.shawken.app.plugins.BasePlugin
import com.shawken.app.plugins.ShawkenPlugin

@ShawkenPlugin
class UHDMoviesPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(UHDMoviesProvider())
    }
}
