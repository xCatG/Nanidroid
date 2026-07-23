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
}

val legacyNativeDirectory = layout.projectDirectory.dir("artifacts/legacy/native")
val emulatorNativeDirectory = layout.projectDirectory.dir("artifacts/emulator/native")
val requiredLegacyNativeLibraries = listOf(
    legacyNativeDirectory.file("armeabi/libkawari8.so"),
    legacyNativeDirectory.file("armeabi/libsatoriya.so"),
)
val requiredEmulatorNativeLibraries = listOf(
    emulatorNativeDirectory.file("arm64-v8a/libkawari8.so"),
    emulatorNativeDirectory.file("arm64-v8a/libsatoriya.so"),
)

abstract class VerifyNativeLibraries : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val libraries: ConfigurableFileCollection

    @get:Input
    abstract val artifactLabel: Property<String>

    @get:Input
    abstract val buildCommand: Property<String>

    @TaskAction
    fun verify() {
        val missing = libraries.files.filterNot { it.isFile }
        if (missing.isNotEmpty()) {
            throw GradleException(
                buildString {
                    appendLine("Missing ${artifactLabel.get()} native libraries:")
                    missing.forEach { appendLine("  - $it") }
                    append("Run `${buildCommand.get()}` before assembling with Gradle.")
                }
            )
        }
    }
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

val verifyLegacyNativeLibraries by tasks.registering(VerifyNativeLibraries::class) {
    group = "verification"
    description = "Checks that PR B1 produced the native libraries packaged by Gradle."
    libraries.from(requiredLegacyNativeLibraries)
    artifactLabel.set("frozen legacy")
    buildCommand.set("docker compose -f docker/legacy/compose.yaml run --rm build")
}

val verifyEmulatorNativeLibraries by tasks.registering(VerifyNativeLibraries::class) {
    group = "verification"
    description = "Checks that the opt-in emulator lane produced both ARM64 engines."
    libraries.from(requiredEmulatorNativeLibraries)
    artifactLabel.set("ARM64 emulator")
    buildCommand.set(
        "docker compose -f docker/legacy/compose.yaml run --rm emulator-native"
    )
}

android {
    namespace = "com.cattailsw.nanidroid"
    // Keep the Ant build's API surface while the build system changes around it.
    compileSdk = 15

    defaultConfig {
        applicationId = "com.cattailsw.nanidroid"
        minSdk = 9
        targetSdk = 13
        versionCode = 6
        versionName = "open_0.1"
        testApplicationId = "com.cattailsw.nanidroid.test"
        testInstrumentationRunner = "android.test.InstrumentationTestRunner"
    }

    buildTypes {
        create("emulator") {
            initWith(getByName("debug"))
            matchingFallbacks += listOf("debug")
            isDebuggable = true
        }
    }

    sourceSets {
        getByName("main") {
            manifest.srcFile("AndroidManifest.xml")
            java.setSrcDirs(listOf("src"))
            aidl.setSrcDirs(listOf("src"))
            res.setSrcDirs(listOf("res"))
            assets.setSrcDirs(listOf("assets"))
            jniLibs.setSrcDirs(listOf(legacyNativeDirectory))
        }
        getByName("test") {
            java.srcDir("test/jvm")
        }
        getByName("androidTest") {
            java.setSrcDirs(listOf("test/device"))
            manifest.srcFile("test/device/AndroidManifest.xml")
        }
        getByName("emulator") {
            jniLibs.srcDir(emulatorNativeDirectory)
        }
    }

    buildFeatures {
        aidl = true
        buildConfig = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation(files("libs/android-support-v4.jar"))
    implementation(files("libs/acra-4.2.3.jar"))
    implementation(files("libs/libGoogleAnalytics.jar"))
    testImplementation("junit:junit:4.13.2")
}

val characterizationTests = listOf(
    "test/jvm/com/cattailsw/nanidroid/DescReaderCharacterizationTest.java",
    "test/jvm/com/cattailsw/nanidroid/SakuraScriptCharacterizationTest.java",
    "test/jvm/com/cattailsw/nanidroid/ShioriEnvelopeCharacterizationTest.java",
    "test/jvm/com/cattailsw/nanidroid/SurfaceDefinitionCharacterizationTest.java",
    "test/jvm/com/cattailsw/nanidroid/ViewServerLifecycleCharacterizationTest.java",
    "test/jvm/com/cattailsw/nanidroid/GhostSwitchingCharacterizationTest.java",
    "test/jvm/com/cattailsw/nanidroid/NarArchiveCharacterizationTest.java",
    "test/jvm/com/cattailsw/nanidroid/install/NarArchiveInventoryValidatorTest.java",
    "test/jvm/com/cattailsw/nanidroid/install/NarDescriptorParserTest.java",
    "test/jvm/com/cattailsw/nanidroid/install/NarZipCentralPreflightTest.java",
    "test/jvm/com/cattailsw/nanidroid/install/NarInstallPlanValidatorTest.java",
    "test/jvm/com/cattailsw/nanidroid/install/NarStagedSourceCopyTest.java",
    "test/jvm/com/cattailsw/nanidroid/install/NarGhostTreePolicyTest.java",
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
        "SurfaceAnimationExecutionCharacterizationTest.java",
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

tasks.named("preBuild").configure {
    dependsOn(verifyLegacyNativeLibraries)
}

tasks.matching { it.name == "preEmulatorBuild" }.configureEach {
    dependsOn(verifyEmulatorNativeLibraries)
}
