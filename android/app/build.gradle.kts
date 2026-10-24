plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "net.kirkanos.mtgportal"
    compileSdk = 37

    defaultConfig {
        applicationId = "net.kirkanos.mtgportal"
        minSdk = 26
        targetSdk = 37
        // CI builds count up with the pipeline, so every release installs over
        // the one before; local builds stay at 1.
        val build = System.getenv("CI_PIPELINE_NUMBER")?.toIntOrNull() ?: 1
        versionCode = build
        versionName = "0.1.$build"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "DEFAULT_BASE_URL", "\"https://mtg.kirkanos.net\"")
    }

    // The release key lives sops-encrypted in signing/ (see README); CI
    // decrypts it and passes it in here. Without it, release builds stay
    // unsigned, which is all a local build needs.
    val keystore = System.getenv("MTG_KEYSTORE")?.let(::file)?.takeIf { it.exists() }
    signingConfigs {
        if (keystore != null) {
            create("release") {
                storeFile = keystore
                storePassword = System.getenv("MTG_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("MTG_KEY_ALIAS")
                keyPassword = System.getenv("MTG_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            // Debug and release can live side by side on the phone.
            applicationIdSuffix = ".debug"
            // Via "adb reverse tcp:8088 tcp:8088" the phone's localhost is the
            // Mac's local stack (docker-compose.local.yml). See README.
            buildConfigField("String", "DEFAULT_BASE_URL", "\"http://127.0.0.1:8088\"")
        }
        release {
            if (keystore != null) signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(project(":scanner"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.navigation.compose)
    implementation(libs.datastore.preferences)
    implementation(libs.coil.compose)
    implementation(libs.coil.okhttp)
    implementation(libs.coil.svg)
    implementation(libs.coroutines.android)
    implementation(libs.serialization.json)
    implementation(libs.retrofit)
    implementation(libs.retrofit.kotlinx)
    implementation(libs.retrofit.scalars)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)

    debugImplementation(libs.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.coroutines.test)
}
