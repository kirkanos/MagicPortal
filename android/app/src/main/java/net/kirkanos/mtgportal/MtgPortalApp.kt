package net.kirkanos.mtgportal

import android.app.Application

/**
 * Application entry. Builds the dependency container once the data layer
 * exists (see the plan: manual DI via AppContainer, no Hilt).
 */
class MtgPortalApp : Application()
