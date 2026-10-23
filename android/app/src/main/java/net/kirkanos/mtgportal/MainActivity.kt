package net.kirkanos.mtgportal

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.kirkanos.mtgportal.core.ui.theme.MtgPortalTheme
import net.kirkanos.mtgportal.core.ui.theme.PortalColors
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

// AppCompatActivity, not ComponentActivity: per-app language switching below
// Android 13 goes through AppCompatDelegate.
class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MtgPortalTheme { ConnectionCheckScreen(BuildConfig.DEFAULT_BASE_URL) }
        }
    }
}

private sealed interface CheckResult {
    data object Idle : CheckResult
    data object Running : CheckResult
    data class Ok(val millis: Long) : CheckResult
    data class HttpError(val code: Int) : CheckResult
    data class NetworkError(val message: String) : CheckResult
}

/**
 * Placeholder for phase 0: proves that the phone reaches the backend before
 * any real screen exists. Replaced by the app shell in phase 1.
 */
@Composable
private fun ConnectionCheckScreen(baseUrl: String) {
    var result by remember { mutableStateOf<CheckResult>(CheckResult.Idle) }
    val scope = rememberCoroutineScope()
    val client = remember {
        OkHttpClient.Builder().callTimeout(10, TimeUnit.SECONDS).build()
    }

    Scaffold { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(stringResource(R.string.setup_title), style = MaterialTheme.typography.headlineSmall)
            Text(stringResource(R.string.setup_base_url, baseUrl), color = PortalColors.TextDim)
            Button(
                enabled = result != CheckResult.Running,
                onClick = {
                    result = CheckResult.Running
                    scope.launch { result = checkHealth(client, baseUrl) }
                },
            ) { Text(stringResource(R.string.setup_test)) }

            when (val r = result) {
                CheckResult.Idle -> Unit
                CheckResult.Running -> Text(stringResource(R.string.setup_testing))
                is CheckResult.Ok -> Text(stringResource(R.string.setup_ok, r.millis), color = PortalColors.Green)
                is CheckResult.HttpError -> Text(stringResource(R.string.setup_http_error, r.code), color = PortalColors.Red)
                is CheckResult.NetworkError -> Text(stringResource(R.string.setup_network_error, r.message), color = PortalColors.Red)
            }
        }
    }
}

private suspend fun checkHealth(client: OkHttpClient, baseUrl: String): CheckResult =
    withContext(Dispatchers.IO) {
        val started = System.nanoTime()
        try {
            client.newCall(Request.Builder().url("$baseUrl/api/health").build()).execute().use { resp ->
                // /api/health answers 204 with no body.
                if (resp.code == 204) CheckResult.Ok((System.nanoTime() - started) / 1_000_000)
                else CheckResult.HttpError(resp.code)
            }
        } catch (e: Exception) {
            CheckResult.NetworkError(e.message ?: e.javaClass.simpleName)
        }
    }
