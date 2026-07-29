import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

plugins {
    id("com.android.application") version "9.3.0"
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.21"
}

abstract class VerifyCharacterizationTestIsolation : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val testSources: ConfigurableFileCollection

    @get:Input
    abstract val expectedSourcePaths: ListProperty<String>

    @get:Internal
    abstract val projectRoot: DirectoryProperty

    @TaskAction
    fun verify() {
        val expected = expectedSourcePaths.get()
            .map { projectRoot.get().asFile.resolve(it).canonicalFile }
            .toSet()
        val actual = testSources.files.map { it.canonicalFile }.toSet()
        val missing = (expected - actual).map { it.invariantSeparatorsPath }.sorted()
        val unexpected = (actual - expected).map { it.invariantSeparatorsPath }.sorted()

        if (missing.isNotEmpty() || unexpected.isNotEmpty()) {
            throw GradleException(
                buildString {
                    appendLine(
                        "Characterization tests temporarily enable Android " +
                            "default-return stubs for app JVM tests."
                    )
                    appendLine(
                        "Isolate or remove unitTests.isReturnDefaultValues before adding broader tests."
                    )
                    if (missing.isNotEmpty()) {
                        appendLine("Missing expected JVM test sources:")
                        missing.forEach { appendLine("  - $it") }
                    }
                    if (unexpected.isNotEmpty()) {
                        appendLine("Unexpected JVM test sources:")
                        unexpected.forEach { appendLine("  - $it") }
                    }
                }
            )
        }
    }
}

abstract class VerifyDeviceCharacterizationTestIsolation : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val testSources: ConfigurableFileCollection

    @get:Input
    abstract val expectedSourcePaths: ListProperty<String>

    @get:Internal
    abstract val projectRoot: DirectoryProperty

    @TaskAction
    fun verify() {
        val expected = expectedSourcePaths.get()
            .map { projectRoot.get().asFile.resolve(it).canonicalFile }
            .toSet()
        val actual = testSources.files.map { it.canonicalFile }.toSet()
        val missing = (expected - actual).map { it.invariantSeparatorsPath }.sorted()
        val unexpected = (actual - expected).map { it.invariantSeparatorsPath }.sorted()

        if (missing.isNotEmpty() || unexpected.isNotEmpty()) {
            throw GradleException(
                buildString {
                    appendLine(
                        "Device characterization tests are an exact, headless " +
                            "instrumentation boundary."
                    )
                    appendLine(
                        "Review and explicitly allowlist every added or removed device test source."
                    )
                    if (missing.isNotEmpty()) {
                        appendLine("Missing expected device test sources:")
                        missing.forEach { appendLine("  - $it") }
                    }
                    if (unexpected.isNotEmpty()) {
                        appendLine("Unexpected device test sources:")
                        unexpected.forEach { appendLine("  - $it") }
                    }
                }
            )
        }
    }
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
                targets += "narfs_full"
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("jni/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildTypes {
        create("emulator") {
            initWith(getByName("debug"))
            matchingFallbacks += listOf("debug")
            isDebuggable = true
        }
        create("device") {
            initWith(getByName("debug"))
            matchingFallbacks += listOf("debug")
            isDebuggable = true
        }
    }

    sourceSets {
        getByName("main") {
            manifest.srcFile("AndroidManifest.xml")
            java.setSrcDirs(listOf("src"))
            java.srcDir("modern/src")
            // AGP 9 built-in Kotlin needs an explicit Kotlin source directory
            // because this legacy layout places Java and Kotlin together.
            kotlin.setSrcDirs(listOf("src"))
            kotlin.srcDir("modern/src")
            aidl.setSrcDirs(listOf("src"))
            res.setSrcDirs(listOf("res"))
            assets.setSrcDirs(listOf("assets"))
        }
        getByName("test") {
            java.srcDir("test/jvm")
            kotlin.srcDir("test/jvm")
        }
        getByName("androidTest") {
            java.setSrcDirs(listOf("test/device"))
            kotlin.setSrcDirs(listOf("test/device"))
            manifest.srcFile("test/device/AndroidManifest.xml")
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
        testBuildType = "emulator"
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.06.00")
    implementation(composeBom)
    androidTestImplementation(composeBom)
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.compose.material3:material3")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    // ComponentActivity is declared only by the device-test manifest, so package
    // its runtime in the test APK rather than changing the production APK.
    androidTestImplementation("androidx.activity:activity:1.13.0")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    // This legacy project exposes emulatorImplementation without a Kotlin DSL
    // accessor. The target-side host is required by ActivityScenario on device.
    add("emulatorImplementation", "androidx.compose.ui:ui-test-manifest")

    // API 36 no longer exposes android.test.*. Keep the frozen legacy
    // characterization sources compiling against their historical API-only
    // facade; the application itself still compiles against API 36 above.
    val legacyTestApi = files(
        "${System.getenv("ANDROID_SDK_ROOT")}/platforms/android-15/android.jar"
    )
    testCompileOnly(legacyTestApi)
    androidTestCompileOnly(legacyTestApi)
    // Local JVM characterization tests use MockContext only as an identity
    // token. API 36 removed android.test.* from its runtime stubs, so provide
    // the frozen API façade at test runtime; affected tests bypass its stub
    // constructor and never invoke Android APIs.
    testRuntimeOnly(legacyTestApi)
    implementation(platform("com.google.firebase:firebase-bom:34.16.0"))
    implementation("com.google.firebase:firebase-crashlytics")
    implementation(files("libs/libGoogleAnalytics.jar"))
    testImplementation("junit:junit:4.13.2")
}

val characterizationTests = listOf(
    "test/jvm/com/cattailsw/nanidroid/HostAndroidStubRule.kt",
    "test/jvm/com/cattailsw/nanidroid/LegacyPlatformSeamTest.kt",
    "test/jvm/com/cattailsw/nanidroid/DescReaderCharacterizationTest.java",
    "test/jvm/com/cattailsw/nanidroid/SakuraScriptCharacterizationTest.java",
    "test/jvm/com/cattailsw/nanidroid/NanidroidShioriCharacterizationTest.java",
    "test/jvm/com/cattailsw/nanidroid/SurfaceDefinitionCharacterizationTest.java",
    "test/jvm/com/cattailsw/nanidroid/compose/SurfaceRenderPlanTest.kt",
    "test/jvm/com/cattailsw/nanidroid/compose/SurfaceCompositorTest.kt",
    "test/jvm/com/cattailsw/nanidroid/compose/SurfaceAnimationSchedulerTest.kt",
    "test/jvm/com/cattailsw/nanidroid/compose/SurfacePointerInteractionTest.kt",
    "test/jvm/com/cattailsw/nanidroid/compose/BalloonPresentationTest.kt",
    "test/jvm/com/cattailsw/nanidroid/GhostSwitchingCharacterizationTest.java",
    "test/jvm/com/cattailsw/nanidroid/GhostShellNameCompatibilityTest.java",
    "test/jvm/com/cattailsw/nanidroid/GhostPresentationFrameTest.java",
    "test/jvm/com/cattailsw/nanidroid/SScriptRunnerPresentationTest.java",
    "test/jvm/com/cattailsw/nanidroid/SSTPBottleSensorCharacterizationTest.java",
    "test/jvm/com/cattailsw/nanidroid/runtime/GhostPresentationReducerTest.java",
    "test/jvm/com/cattailsw/nanidroid/runtime/GhostStageLayoutPolicyTest.java",
    "test/jvm/com/cattailsw/nanidroid/runtime/NanidroidCoordinatorTest.java",
    "test/jvm/com/cattailsw/nanidroid/runtime/SakuraScriptPresentationReducerTest.java",
    "test/jvm/com/cattailsw/nanidroid/runtime/SakuraScriptPresentationInterpreterTest.java",
    "test/jvm/com/cattailsw/nanidroid/runtime/SakuraScriptInteractionInterpreterTest.java",
    "test/jvm/com/cattailsw/nanidroid/runtime/KotlinGhostPresentationRuntimeTest.kt",
    "test/jvm/com/cattailsw/nanidroid/NarArchiveCharacterizationTest.java",
    "test/jvm/com/cattailsw/nanidroid/install/NarArchiveInventoryValidatorTest.kt",
    "test/jvm/com/cattailsw/nanidroid/install/NarDescriptorParserTest.kt",
    "test/jvm/com/cattailsw/nanidroid/install/NarZipCentralPreflightTest.java",
    "test/jvm/com/cattailsw/nanidroid/install/NarInstallPlanValidatorTest.java",
    "test/jvm/com/cattailsw/nanidroid/install/NarStagedSourceCopyTest.java",
    "test/jvm/com/cattailsw/nanidroid/install/NarGhostTreePolicyTest.kt",
    "test/jvm/com/cattailsw/nanidroid/install/NarFilesystemInspectorTest.java",
    "test/jvm/com/cattailsw/nanidroid/install/NarStagedTreeInventoryTest.kt",
      "test/jvm/com/cattailsw/nanidroid/install/NarStagedTreeTest.kt",
    "test/jvm/com/cattailsw/nanidroid/install/NarRetainedOverlayPolicyTest.kt",
    "test/jvm/com/cattailsw/nanidroid/install/NarRetainedOverlayCoordinatorTest.kt",
    "test/jvm/com/cattailsw/nanidroid/install/NarTransactionalInstallerTest.java",
    "test/jvm/com/cattailsw/nanidroid/install/NarContentUriImportTest.kt",
)
val jvmTestSources = files(
    fileTree("src") {
        include("test*/**/*.java", "test*/**/*.kt")
    },
    fileTree("test/jvm") {
        include("**/*.java", "**/*.kt")
    },
)
val deviceCharacterizationTests = listOf(
    "test/device/com/cattailsw/nanidroid/" +
        "SurfaceRenderingCharacterizationTest.java",
    "test/device/com/cattailsw/nanidroid/" +
        "SurfaceAnimationExecutionCharacterizationTest.kt",
    "test/device/com/cattailsw/nanidroid/PreferencesScreenTest.kt",
    "test/device/com/cattailsw/nanidroid/NanidroidLifecycleInstrumentationTest.java",
    "test/device/com/cattailsw/nanidroid/compose/NanidroidComposeShellTest.kt",
    "test/device/com/cattailsw/nanidroid/install/" +
        "NarFilesystemInspectorInstrumentationTest.java",
    "test/device/com/cattailsw/nanidroid/install/" +
        "NarStagedTreeInstrumentationTest.kt",
)
val deviceTestSources = files(
    fileTree("test/device") {
        include("**/*.java", "**/*.kt")
    },
)

val verifyCharacterizationTestIsolation by
    tasks.registering(VerifyCharacterizationTestIsolation::class) {
    group = "verification"
    description = "Keeps Android default-return stubs isolated to allowlisted characterizations."
    testSources.from(jvmTestSources)
    expectedSourcePaths.set(characterizationTests)
    projectRoot.set(layout.projectDirectory)
}

val verifyDeviceCharacterizationTestIsolation by
    tasks.registering(VerifyDeviceCharacterizationTestIsolation::class) {
    group = "verification"
    description = "Keeps real-framework device characterizations exactly allowlisted."
    testSources.from(deviceTestSources)
    expectedSourcePaths.set(deviceCharacterizationTests)
    projectRoot.set(layout.projectDirectory)
}

tasks.matching {
    it.name.startsWith("test") && it.name.endsWith("UnitTest")
}.configureEach {
    dependsOn(verifyCharacterizationTestIsolation)
}

tasks.named("check").configure {
    dependsOn(verifyDeviceCharacterizationTestIsolation)
}

tasks.matching {
    it.name.startsWith("compile") && it.name.contains("AndroidTest")
}.configureEach {
    dependsOn(verifyDeviceCharacterizationTestIsolation)
}
