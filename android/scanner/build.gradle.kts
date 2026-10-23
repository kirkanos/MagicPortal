plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
}

// The camera card scanner: CameraX, on-device text recognition (ML Kit) and,
// later, the on-device catalog and image hash index. Everything here stays out
// of :app, which only knows ScannerEntry.
android {
    namespace = "net.kirkanos.mtgportal.scanner"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.coroutines.android)

    implementation(libs.camerax.camera2)
    implementation(libs.camerax.lifecycle)
    implementation(libs.camerax.view)
    // Bundled model: works offline from the first scan. The unbundled variant
    // downloads the model through Play services on first use, which fails
    // exactly where the scanner matters most - without network.
    implementation(libs.mlkit.text)

    testImplementation(libs.junit)
    // org.json for reading the fixtures; Android's own copy is a stub in JVM tests.
    testImplementation(libs.json)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
}
