package net.kirkanos.mtgportal.core.ui.components

import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.sp
import android.widget.Toast
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import net.kirkanos.mtgportal.R
import net.kirkanos.mtgportal.core.data.AuthState
import net.kirkanos.mtgportal.core.network.ApiResult
import net.kirkanos.mtgportal.core.ui.appContainer
import net.kirkanos.mtgportal.core.ui.theme.PortalColors

/** True when writes are possible: no password on the server, or logged in. */
@Composable
fun isUnlocked(): Boolean {
    val auth by appContainer().auth.state.collectAsStateWithLifecycle()
    return auth == AuthState.Open || auth == AuthState.Unlocked
}

/**
 * The wishlist star of the web UI (wishButtonHTML / initWishButtons in
 * shared.js). Marking is a write, so like there the star only exists with
 * write access. Every star follows the shared name set, so toggling one
 * updates all others showing the same card.
 *
 * @param label text after the star, e.g. "Wunschliste"; null for the bare star
 */
@Composable
fun WishStar(name: String, modifier: Modifier = Modifier, label: String? = null) {
    if (!isUnlocked()) return
    val c = appContainer()
    val names by c.wishlist.names.collectAsStateWithLifecycle()
    val on = name.lowercase() in names
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val failed = androidx.compose.ui.res.stringResource(R.string.wish_save_failed, "%s")

    TextButton(
        modifier = modifier,
        onClick = {
            scope.launch {
                val r = c.wishlist.set(name, !on)
                val msg = when (r) {
                    is ApiResult.HttpError -> "HTTP ${r.code}: ${r.message}"
                    is ApiResult.NetworkError -> r.cause.message ?: r.cause.javaClass.simpleName
                    is ApiResult.Success -> null
                }
                if (msg != null) Toast.makeText(context, failed.replace("%s", msg), Toast.LENGTH_LONG).show()
            }
        },
    ) {
        Text(
            (if (on) "★" else "☆") + (label?.let { " $it" } ?: ""),
            color = if (on) PortalColors.Accent else PortalColors.TextDim,
            fontSize = if (label == null) 20.sp else 14.sp,
        )
    }
}
