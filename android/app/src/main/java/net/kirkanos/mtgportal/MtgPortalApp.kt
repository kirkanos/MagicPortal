package net.kirkanos.mtgportal

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import net.kirkanos.mtgportal.di.AppContainer

class MtgPortalApp : Application(), SingletonImageLoader.Factory {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }

    // Every AsyncImage uses the container's loader: SVG support and the
    // image-only HTTP client that never carries the upload password.
    override fun newImageLoader(context: PlatformContext): ImageLoader = container.imageLoader
}
