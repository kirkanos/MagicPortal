package net.kirkanos.mtgportal

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import net.kirkanos.mtgportal.core.ui.theme.MtgPortalTheme
import net.kirkanos.mtgportal.feature.settings.SetupScreen
import net.kirkanos.mtgportal.navigation.AppShell

// AppCompatActivity, not ComponentActivity: per-app language switching below
// Android 13 goes through AppCompatDelegate.
class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val settings = (application as MtgPortalApp).container.settings
        setContent {
            MtgPortalTheme {
                // Until the setup is through, password step included.
                var setup by rememberSaveable { mutableStateOf(!settings.hasServer) }
                if (setup) SetupScreen(onDone = { setup = false }) else AppShell()
            }
        }
    }
}
