package net.kirkanos.mtgportal.core.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import net.kirkanos.mtgportal.R
import net.kirkanos.mtgportal.core.network.ApiResult
import net.kirkanos.mtgportal.core.ui.theme.PortalColors

/** One message per error kind, so "no server" never looks like "server said no". */
@Composable
fun errorText(error: ApiResult<Nothing>): String = when (error) {
    is ApiResult.HttpError ->
        if (error.code == 403) stringResource(R.string.common_wrong_password)
        else stringResource(R.string.common_server_error, error.code, error.message)
    is ApiResult.NetworkError ->
        stringResource(R.string.common_server_unreachable, error.cause.message ?: error.cause.javaClass.simpleName)
    is ApiResult.Success -> ""
}

@Composable
fun ErrorRetry(error: ApiResult<Nothing>, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxWidth().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(errorText(error), color = PortalColors.Red)
        OutlinedButton(onClick = onRetry) { Text(stringResource(R.string.common_retry)) }
    }
}
