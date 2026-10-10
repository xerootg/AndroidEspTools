package io.github.xerootg.esptools

import android.app.Application
import io.github.xerootg.esptools.session.DeviceSession
import io.github.xerootg.esptools.session.Settings

class EspToolsApp : Application() {
    lateinit var settings: Settings
        private set
    lateinit var session: DeviceSession
        private set

    override fun onCreate() {
        super.onCreate()
        settings = Settings(this)
        session = DeviceSession(this, settings)
    }
}
