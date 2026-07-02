plugins {
    alias(libs.plugins.android.library)
}

val organicMapsAndroid = rootProject.file("third_party/organicmaps/android")

android {
    namespace = "app.organicmaps.sdk.location"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
    }

    sourceSets {
        getByName("main") {
            manifest.srcFile("src/main/AndroidManifest.xml")
            java.srcDir(organicMapsAndroid.resolve("sdk/location/core/src/main/java"))
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(libs.androidx.annotation)
    implementation(libs.androidx.core.ktx)
}
