package io.github.sriram_pr.snaggle

import android.app.Application
import android.content.Context
import io.github.sriram_pr.snaggle.data.SettingsStore
import io.github.sriram_pr.snaggle.data.SnaggleDb
import io.github.sriram_pr.snaggle.download.Notifications
import io.github.sriram_pr.snaggle.engine.EngineStatus
import io.github.sriram_pr.snaggle.engine.chaquopy.ChaquopyEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class SnaggleApp : Application() {
    val db: SnaggleDb by lazy { SnaggleDb.create(this) }
    val engine: ChaquopyEngine by lazy { ChaquopyEngine(this) }
    val settings: SettingsStore by lazy { SettingsStore(this) }
    /** For work that should outlive a screen (e.g. the engine update check on launch). */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val engineLock = Mutex()
    @Volatile private var engineReady = false

    /** Starts Python and loads yt-dlp once per process; later calls return immediately. */
    suspend fun readyEngine(): ChaquopyEngine {
        engineLock.withLock {
            if (!engineReady) {
                val status = engine.init()
                check(status is EngineStatus.Ready) { "Engine failed to start: $status" }
                engineReady = true
            }
        }
        return engine
    }

    override fun onCreate() {
        super.onCreate()
        Notifications.createChannels(this)
    }
}

val Context.snaggle: SnaggleApp get() = applicationContext as SnaggleApp
