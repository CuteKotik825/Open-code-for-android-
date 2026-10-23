package com.pocketcode

import android.app.Application
import com.pocketcode.ui.ChatController

class PocketCodeApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppLog.init(this)
    }

    /** Один контроллер на всё приложение: агент не зависит от жизни экрана. */
    val controller: ChatController by lazy { ChatController(this) }
}
