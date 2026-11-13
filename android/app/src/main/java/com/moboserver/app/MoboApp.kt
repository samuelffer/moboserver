package com.moboserver.app

import android.app.Application
import com.moboserver.app.core.CrashLog
import com.moboserver.app.core.ServerController
import com.moboserver.app.core.Store

class MoboApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashLog.install(this)
        ServerController.init(this)
        Store.init(this)
    }
}
