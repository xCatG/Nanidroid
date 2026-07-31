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
    androidTestImplementation("androidx.test:core:1.7.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    // ComponentActivity is declared only by the device-test manifest, so package
    // its runtime in the test APK rather than changing the production APK.
    androidTestImplementation("androidx.activity:activity:1.13.0")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    implementation(platform("com.google.firebase:firebase-bom:34.16.0"))
    implementation("com.google.firebase:firebase-crashlytics")
    implementation(files("libs/libGoogleAnalytics.jar"))
    testImplementation("junit:junit:4.13.2")
    testImplementation("io.mockk:mockk:1.14.11")
}

val characterizationTests = listOf(
    "src/test/java/com/cattailsw/nanidroid/HostAndroidStubRule.kt",
    "src/test/java/com/cattailsw/nanidroid/LegacyPlatformSeamTest.kt",
    "src/test/java/com/cattailsw/nanidroid/DescReaderCharacterizationTest.java",
    "src/test/java/com/cattailsw/nanidroid/SakuraScriptCharacterizationTest.java",
    "src/test/java/com/cattailsw/nanidroid/NanidroidShioriCharacterizationTest.java",
    "src/test/java/com/cattailsw/nanidroid/SurfaceDefinitionCharacterizationTest.java",
    "src/test/java/com/cattailsw/nanidroid/compose/SurfaceRenderPlanTest.kt",
    "src/test/java/com/cattailsw/nanidroid/compose/SurfaceCompositorTest.kt",
    "src/test/java/com/cattailsw/nanidroid/compose/SurfaceAnimationSchedulerTest.kt",
    "src/test/java/com/cattailsw/nanidroid/compose/SurfacePointerInteractionTest.kt",
    "src/test/java/com/cattailsw/nanidroid/compose/BalloonPresentationTest.kt",
    "src/test/java/com/cattailsw/nanidroid/GhostSwitchingCharacterizationTest.java",
    "src/test/java/com/cattailsw/nanidroid/GhostSwitchRequestTest.kt",
    "src/test/java/com/cattailsw/nanidroid/GhostShellNameCompatibilityTest.java",
    "src/test/java/com/cattailsw/nanidroid/GhostPresentationFrameTest.java",
    "src/test/java/com/cattailsw/nanidroid/SScriptRunnerPresentationTest.java",
    "src/test/java/com/cattailsw/nanidroid/BootDispatchStateTest.kt",
    "src/test/java/com/cattailsw/nanidroid/SScriptRunnerBootDispatchTest.java",
    "src/test/java/com/cattailsw/nanidroid/SSTPBottleSensorCharacterizationTest.java",
    "src/test/java/com/cattailsw/nanidroid/runtime/GhostPresentationReducerTest.java",
    "src/test/java/com/cattailsw/nanidroid/runtime/GhostStageLayoutPolicyTest.java",
    "src/test/java/com/cattailsw/nanidroid/runtime/SakuraScriptPresentationReducerTest.java",
    "src/test/java/com/cattailsw/nanidroid/runtime/SakuraScriptPresentationInterpreterTest.java",
    "src/test/java/com/cattailsw/nanidroid/runtime/SakuraScriptInteractionInterpreterTest.java",
    "src/test/java/com/cattailsw/nanidroid/runtime/KotlinGhostPresentationRuntimeTest.kt",
    "src/test/java/com/cattailsw/nanidroid/NarArchiveCharacterizationTest.java",
    "src/test/java/com/cattailsw/nanidroid/install/NarArchiveInventoryValidatorTest.kt",
    "src/test/java/com/cattailsw/nanidroid/install/NarDescriptorParserTest.kt",
    "src/test/java/com/cattailsw/nanidroid/install/NarZipCentralPreflightTest.java",
    "src/test/java/com/cattailsw/nanidroid/install/NarInstallPlanValidatorTest.java",
    "src/test/java/com/cattailsw/nanidroid/install/NarStagedSourceCopyTest.java",
    "src/test/java/com/cattailsw/nanidroid/install/NarGhostTreePolicyTest.kt",
    "src/test/java/com/cattailsw/nanidroid/install/NarFilesystemInspectorTest.java",
    "src/test/java/com/cattailsw/nanidroid/install/NarStagedTreeInventoryTest.kt",
    "src/test/java/com/cattailsw/nanidroid/install/NarStagedTreeTest.kt",
    "src/test/java/com/cattailsw/nanidroid/install/NarRetainedOverlayPolicyTest.kt",
    "src/test/java/com/cattailsw/nanidroid/install/NarRetainedOverlayCoordinatorTest.kt",
    "src/test/java/com/cattailsw/nanidroid/install/NarTransactionalInstallerTest.java",
    "src/test/java/com/cattailsw/nanidroid/install/NarContentUriImportTest.kt",
)
val jvmTestSources = files(
    fileTree("src/test/java") {
        include("**/*.java", "**/*.kt")
    },
)
val deviceCharacterizationTests = listOf(
    "src/androidTest/java/com/cattailsw/nanidroid/" +
        "SurfaceRenderingCharacterizationTest.java",
    "src/androidTest/java/com/cattailsw/nanidroid/" +
        "SurfaceAnimationExecutionCharacterizationTest.kt",
    "src/androidTest/java/com/cattailsw/nanidroid/PreferencesScreenTest.kt",
    "src/androidTest/java/com/cattailsw/nanidroid/NanidroidLifecycleInstrumentationTest.java",
    "src/androidTest/java/com/cattailsw/nanidroid/compose/NanidroidComposeShellTest.kt",
    "src/androidTest/java/com/cattailsw/nanidroid/install/" +
        "NarFilesystemInspectorInstrumentationTest.java",
    "src/androidTest/java/com/cattailsw/nanidroid/install/" +
        "NarStagedTreeInstrumentationTest.kt",
)
val deviceTestSources = files(
    fileTree("src/androidTest") {
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

