pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "mtg-portal-android"

// :app is everything the web UI does. :scanner holds the camera, ML Kit and the
// on-device catalog, so their dependencies (and Room's annotation processing)
// stay out of the main module.
include(":app", ":scanner")
