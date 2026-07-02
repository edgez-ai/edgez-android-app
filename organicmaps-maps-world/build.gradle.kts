plugins {
    alias(libs.plugins.android.library)
}

val organicMapsAndroid = rootProject.file("third_party/organicmaps/android")

android {
    namespace = "app.organicmaps.sdk.maps.world"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
    }

    sourceSets {
        getByName("main") {
            manifest.srcFile("src/main/AndroidManifest.xml")
            assets.srcDir(organicMapsAndroid.resolve("sdk/maps/world/src/main/assets"))
        }
    }
}
