package io.github.sriram_pr.snaggle

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import io.github.sriram_pr.snaggle.download.EngineUpdates
import io.github.sriram_pr.snaggle.ui.HistoryScreen
import io.github.sriram_pr.snaggle.ui.SnaggleTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = snaggle
        app.appScope.launch { runCatching { EngineUpdates.checkIfDue(app) } }
        setContent { SnaggleTheme { HistoryScreen(onSettings = { startActivity(Intent(this, SettingsActivity::class.java)) }) } }
    }
}
