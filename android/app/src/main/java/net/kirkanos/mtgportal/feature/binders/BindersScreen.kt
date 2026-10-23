package net.kirkanos.mtgportal.feature.binders

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import net.kirkanos.mtgportal.R
import net.kirkanos.mtgportal.core.network.ApiResult
import net.kirkanos.mtgportal.core.network.dto.BinderDto
import net.kirkanos.mtgportal.core.network.safeCall
import net.kirkanos.mtgportal.core.ui.Format
import net.kirkanos.mtgportal.core.ui.components.ErrorRetry
import net.kirkanos.mtgportal.core.ui.containerViewModel
import net.kirkanos.mtgportal.core.ui.theme.PortalColors
import net.kirkanos.mtgportal.di.AppContainer

class BindersViewModel(private val c: AppContainer) : ViewModel() {
    private val _state = MutableStateFlow<ApiResult<List<BinderDto>>?>(null)
    val state: StateFlow<ApiResult<List<BinderDto>>?> = _state.asStateFlow()

    init { load() }

    fun load() = viewModelScope.launch {
        _state.value = null
        _state.value = safeCall { c.api.binders() }
    }
}

/**
 * Folders or lists (binders.js; one page per type, same data). Tapping one
 * opens the card grid filtered to it, like index.html?binder= on the web.
 */
@Composable
fun BindersScreen(type: String, onOpen: (String) -> Unit) {
    val vm = containerViewModel { BindersViewModel(it) }
    val st by vm.state.collectAsStateWithLifecycle()

    when (val r = st) {
        null -> Column(Modifier.fillMaxSize().padding(24.dp)) { CircularProgressIndicator() }
        is ApiResult.HttpError -> ErrorRetry(r, onRetry = { vm.load() })
        is ApiResult.NetworkError -> ErrorRetry(r, onRetry = { vm.load() })
        is ApiResult.Success -> {
            val list = r.data.filter { it.type.equals(type, ignoreCase = true) && it.name.isNotEmpty() }
            LazyColumn(
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                item {
                    Text(
                        stringResource(
                            if (type == "list") R.string.binders_count_list else R.string.binders_count_binder,
                            list.size,
                        ),
                        color = PortalColors.TextDim,
                    )
                }
                if (list.isEmpty()) item { Text(stringResource(R.string.binders_empty), color = PortalColors.TextDim) }
                items(list, key = { it.name }) { b ->
                    Column(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(PortalColors.BgCard)
                            .clickable { onOpen(b.name) }.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(b.name, style = MaterialTheme.typography.titleMedium)
                        Row {
                            Text(
                                stringResource(R.string.binders_cards, Format.count(b.total)),
                                color = PortalColors.TextDim, fontSize = 13.sp, modifier = Modifier.weight(1f),
                            )
                            Text(Format.eur(b.marketValue), color = PortalColors.Accent, fontSize = 13.sp)
                        }
                    }
                }
            }
        }
    }
}
