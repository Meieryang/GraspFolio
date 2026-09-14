package io.graspfolio.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import io.graspfolio.app.ui.theme.GraspFolioTheme

/** Real reader with private test documents; never adds fixtures to the user's recent books. */
class ReaderLoadingTestActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val uri = requireNotNull(intent.data)
        setContent {
            GraspFolioTheme(darkTheme = false, dynamicColor = false) {
                PdfReader(uri, onOpenAnother = { finish() }, onExit = { finish() }, recordRecent = false)
            }
        }
    }
}
