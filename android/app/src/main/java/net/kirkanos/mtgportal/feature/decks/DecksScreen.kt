package net.kirkanos.mtgportal.feature.decks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.kirkanos.mtgportal.R
import net.kirkanos.mtgportal.core.ui.theme.PortalColors
import net.kirkanos.mtgportal.core.ui.icons.AppIconView
import net.kirkanos.mtgportal.core.ui.icons.Icons

/** decks.html: a placeholder there too, buttons included and disabled. */
@Composable
fun DecksScreen() {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterVertically),
    ) {
        AppIconView(Icons.Planeswalker, PortalColors.Accent, size = 56.dp)
        Text(stringResource(R.string.decks_title), style = MaterialTheme.typography.titleLarge)
        Text(stringResource(R.string.decks_text), color = PortalColors.TextDim, textAlign = TextAlign.Center)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {}, enabled = false) { Text(stringResource(R.string.decks_import)) }
            OutlinedButton(onClick = {}, enabled = false) { Text(stringResource(R.string.decks_export)) }
        }
    }
}
