plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.cattailsw.nanidroid"
    // PR46 validates the Android 37 preview surface after the API-36 security
    // boundary and real-emulator runtime proof.
    compileSdk = 37


    defaultConfig {
        applicationId = "com.cattailsw.nanidroid"
        // Android 12 / API 31 is the approved product minimum and enables
        // the Compose migration that replaces the legacy View renderer.
        minSdk = 31
        targetSdk = 37
        versionCode = 6
        versionName = "open_0.1"
        testApplicationId = "com.cattailsw.nanidroid.test"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
        externalNativeBuild {
            cmake {
                arguments += listOf(
                    "-DNANIDROID_BUILD_NARFS_FULL_JNI_CANDIDATE=ON",
                    "-DNANIDROID_BUILD_NARFS_STAGE_CANDIDATE=ON",
                    "-DNANIDROID_BUILD_NARFS_SHA256_CANDIDATE=ON",
                )
                targets += listOf("narfs_full", "satoriya", "ssu", "kawari8", "yaya")
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("jni/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildFeatures {
        aidl = true
        buildConfig = false
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    testOptions {
        testBuildType = "debug"
    }
}

dependencies {
    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)
    androidTestImplementation(composeBom)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.espresso)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    // ComponentActivity is declared only by the device-test manifest, so package
    // its runtime in the test APK rather than changing the production APK.
    androidTestImplementation(libs.androidx.activity)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.crashlytics)
    implementation(files("libs/libGoogleAnalytics.jar"))
    testImplementation(libs.junit)
    testImplementation(libs.mockk)
}

