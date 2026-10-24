package net.kirkanos.mtgportal

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import net.kirkanos.mtgportal.core.ui.theme.MtgPortalTheme
import net.kirkanos.mtgportal.navigation.AppShell

// AppCompatActivity, not ComponentActivity: per-app language switching below
// Android 13 goes through AppCompatDelegate.
class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { MtgPortalTheme { AppShell() } }
    }
}
