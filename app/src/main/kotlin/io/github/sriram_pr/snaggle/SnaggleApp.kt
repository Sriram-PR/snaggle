package io.github.sriram_pr.snaggle

import android.app.Application
import android.content.Context
import io.github.sriram_pr.snaggle.data.SnaggleDb
import io.github.sriram_pr.snaggle.download.Notifications
import io.github.sriram_pr.snaggle.engine.EngineStatus
import io.github.sriram_pr.snaggle.engine.chaquopy.ChaquopyEngine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class SnaggleApp : Application() {
    val db: SnaggleDb by lazy { SnaggleDb.create(this) }
    val engine: ChaquopyEngine by lazy { ChaquopyEngine(this) }

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
