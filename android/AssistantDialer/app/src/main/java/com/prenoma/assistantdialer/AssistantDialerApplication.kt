package com.prenoma.assistantdialer

import android.app.Application
import com.prenoma.assistantdialer.telecom.TelecomRegistrar

class AssistantDialerApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        TelecomRegistrar.register(this)
    }
}
