import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ListProperty
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
val requiredLegacyNativeLibraries = listOf(
    legacyNativeDirectory.file("armeabi/libkawari8.so"),
    legacyNativeDirectory.file("armeabi/libsatoriya.so"),
)

abstract class VerifyLegacyNativeLibraries : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val libraries: ConfigurableFileCollection

    @TaskAction
    fun verify() {
        val missing = libraries.files.filterNot { it.isFile }
        if (missing.isNotEmpty()) {
            throw GradleException(
                buildString {
                    appendLine("Missing frozen legacy native libraries:")
                    missing.forEach { appendLine("  - $it") }
                    append(
                        "Run `docker compose -f docker/legacy/compose.yaml " +
                            "run --rm build` before assembling with Gradle."
                    )
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

val verifyLegacyNativeLibraries by tasks.registering(VerifyLegacyNativeLibraries::class) {
    group = "verification"
    description = "Checks that PR B1 produced the native libraries packaged by Gradle."
    libraries.from(requiredLegacyNativeLibraries)
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
)
val jvmTestSources = files(
    fileTree("src") {
        include("test*/**/*.java", "test*/**/*.kt")
    },
    fileTree("test/jvm") {
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

tasks.matching {
    it.name.startsWith("test") && it.name.endsWith("UnitTest")
}.configureEach {
    dependsOn(verifyCharacterizationTestIsolation)
}

tasks.named("preBuild").configure {
    dependsOn(verifyLegacyNativeLibraries)
}
