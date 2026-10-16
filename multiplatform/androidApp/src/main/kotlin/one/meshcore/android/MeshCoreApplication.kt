package one.meshcore.android

import android.app.Application

/** Builds the process-scoped [AppContainer] once and exposes it to the Activity. */
class MeshCoreApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        CrashLogRecorder.install()
        container = AppContainer(this)
    }
}
