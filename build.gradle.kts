import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
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

abstract class VerifyD1TestIsolation : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val testSources: ConfigurableFileCollection

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val allowedSource: RegularFileProperty

    @TaskAction
    fun verify() {
        val allowed = allowedSource.get().asFile
        val unexpected = testSources.files
            .filterNot { it == allowed }
            .map { it.invariantSeparatorsPath }
            .sorted()

        if (unexpected.isNotEmpty()) {
            throw GradleException(
                buildString {
                    appendLine(
                        "D1 temporarily enables Android default-return stubs for app JVM tests."
                    )
                    appendLine(
                        "Isolate or remove unitTests.isReturnDefaultValues before adding broader tests."
                    )
                    appendLine("Unexpected JVM test sources:")
                    unexpected.forEach { appendLine("  - $it") }
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

val d1CharacterizationTest =
    "test/jvm/com/cattailsw/nanidroid/DescReaderCharacterizationTest.java"
val d1JvmTestSources = files(
    fileTree("src/test") {
        include("**/*.java", "**/*.kt")
    },
    fileTree("test/jvm") {
        include("**/*.java", "**/*.kt")
    },
)

val verifyD1TestIsolation by tasks.registering(VerifyD1TestIsolation::class) {
    group = "verification"
    description = "Keeps Android default-return stubs isolated to the D1 characterization."
    testSources.from(d1JvmTestSources)
    allowedSource.set(layout.projectDirectory.file(d1CharacterizationTest))
}

tasks.matching { it.name == "testDebugUnitTest" }.configureEach {
    dependsOn(verifyD1TestIsolation)
}

tasks.named("preBuild").configure {
    dependsOn(verifyLegacyNativeLibraries)
}
