package io.github.sriram_pr.snaggle

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import io.github.sriram_pr.snaggle.engine.Links
import io.github.sriram_pr.snaggle.ui.DownloadSheet
import io.github.sriram_pr.snaggle.ui.SnaggleTheme

/** Share-sheet target: a bottom sheet over whatever app shared the link. */
class ShareActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // No activity transition: the sheet's own slide is the only motion.
            overrideActivityTransition(OVERRIDE_TRANSITION_OPEN, 0, 0)
            overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
        }
        val url = intent.getStringExtra(Intent.EXTRA_TEXT)?.let(Links::extract)
        if (url == null) {
            Toast.makeText(this, R.string.share_no_link, Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        setContent { SnaggleTheme { DownloadSheet(url) { finish() } } }
    }
}
