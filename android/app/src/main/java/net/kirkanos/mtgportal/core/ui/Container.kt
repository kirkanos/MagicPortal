package net.kirkanos.mtgportal.core.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
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
