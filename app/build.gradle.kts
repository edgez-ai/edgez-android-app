plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

val buildEdgezLibp2pAndroid by tasks.registering(Exec::class) {
    workingDir = rootProject.projectDir
    commandLine(rootProject.file("tools/build-edgez-libp2p-android.sh").absolutePath)
    environment("GOCACHE", rootProject.layout.projectDirectory.dir(".gocache").asFile.absolutePath)
    inputs.files(fileTree(rootProject.file("native/edgez-libp2p")) {
        include("**/*.go", "go.mod", "go.sum")
    })
    inputs.file(rootProject.file("tools/build-edgez-libp2p-android.sh"))
    outputs.dir(rootProject.file("app/src/main/jniLibs"))

    doFirst {
        val ndkDir = android.ndkDirectory
        if (ndkDir.exists()) {
            environment("ANDROID_NDK_HOME", ndkDir.absolutePath)
        }
    }
}

android {
    namespace = "ai.edgez.edgez"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "ai.edgez.edgez"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        buildConfig = true
        compose = true
    }
}

tasks.named("preBuild") {
    dependsOn(buildEdgezLibp2pAndroid)
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material3.adaptive.navigation.suite)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(project(":organicmaps-sdk"))
    implementation(project(":organicmaps-maps-world"))
    implementation(libs.protobuf.javalite)
    implementation(libs.usbSerialForAndroid)
    coreLibraryDesugaring(libs.android.tools.desugaring)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
