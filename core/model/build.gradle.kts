plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.haoli.swipegallery.core.model"
    compileSdk = 37

    defaultConfig {
        minSdk = 30
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
