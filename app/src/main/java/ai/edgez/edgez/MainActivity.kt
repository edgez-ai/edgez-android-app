package ai.edgez.edgez

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import ai.edgez.edgez.ui.theme.EdgeZTheme

class MainActivity : ComponentActivity() {
    private var pendingDriverInstall by mutableStateOf<MarketplaceDriverInstallRequest?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingDriverInstall = MarketplaceDriverInstallRequest.fromIntent(intent)
        enableEdgeToEdge()
        setContent {
            EdgeZTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    EdgeZApp(
                        driverInstallRequest = pendingDriverInstall,
                        onDriverInstallHandled = { pendingDriverInstall = null },
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingDriverInstall = MarketplaceDriverInstallRequest.fromIntent(intent)
    }
}
