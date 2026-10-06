package io.github.sriram_pr.snaggle

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import io.github.sriram_pr.snaggle.ui.HistoryScreen
import io.github.sriram_pr.snaggle.ui.SnaggleTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { SnaggleTheme { HistoryScreen() } }
    }
}
