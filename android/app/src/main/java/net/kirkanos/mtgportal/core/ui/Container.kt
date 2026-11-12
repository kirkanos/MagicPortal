package net.kirkanos.mtgportal.core.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.launch
import net.kirkanos.mtgportal.MtgPortalApp
import net.kirkanos.mtgportal.di.AppContainer

@Composable
fun appContainer(): AppContainer = (LocalContext.current.applicationContext as MtgPortalApp).container

/** A ViewModel built from the app container, scoped like any other. */
@Composable
inline fun <reified VM : ViewModel> containerViewModel(crossinline create: (AppContainer) -> VM): VM {
    val container = appContainer()
    return viewModel(factory = viewModelFactory { initializer { create(container) } })
}

/**
 * A screen's sort order that survives restarts, like the web's
 * localStorage['mtg_sort_<page>']. Setting it stores it.
 */
@Composable
fun rememberSort(page: String, default: String): MutableState<String> {
    val settings = appContainer().settings
    val scope = rememberCoroutineScope()
    val state = rememberSaveable(page) { mutableStateOf(settings.sortFor(page) ?: default) }
    return remember(state, scope) {
        object : MutableState<String> by state {
            override var value: String
                get() = state.value
                set(v) {
                    state.value = v
                    scope.launch { settings.setSort(page, v) }
                }
        }
    }
}
