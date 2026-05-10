package ai.openclaw.companion

import android.app.Application
import ai.openclaw.companion.service.ClawAccessibilityService
import ai.openclaw.companion.service.ClawNotificationListener

class ClawApp : Application() {

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    companion object {
        @Volatile
        var instance: ClawApp? = null
            private set

        const val DEFAULT_PORT = 18790
    }
}