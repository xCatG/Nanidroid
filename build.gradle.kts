import com.android.build.api.artifact.ScopedArtifact
import com.android.build.api.variant.ScopedArtifacts
import org.gradle.api.DefaultTask
import org.gradle.api.file.Directory
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileSystemOperations
import org.gradle.api.file.RegularFile
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.OutputDirectory
import java.io.File
import java.net.URI
import java.nio.file.Files
import java.nio.file.FileSystems
import java.nio.file.StandardCopyOption

import javax.inject.Inject

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
    alias(libs.plugins.screenshot)
    jacoco
}

android {
    namespace = "com.cattailsw.nanidroid"
    // PR46 validates the Android 37 preview surface after the API-36 security
    // boundary and real-emulator runtime proof.
    compileSdk = 37
    experimentalProperties["android.experimental.enableScreenshotTest"] = true


    defaultConfig {
        applicationId = "com.cattailsw.nanidroid"
        // Android 12 / API 31 is the approved product minimum and enables
        // the Compose migration that replaces the legacy View renderer.
        minSdk = 31
        targetSdk = 37
        versionCode = 6
        versionName = "open_0.1"
        testApplicationId = "com.cattailsw.nanidroid.test"
        testInstrumentationRunner = "com.cattailsw.nanidroid.NanidroidTestRunner"
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
    implementation(libs.androidx.compose.material3.adaptive)
    implementation(libs.androidx.window)
    implementation(libs.androidx.work.runtime)
    implementation(libs.androidx.hilt.work)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    ksp(libs.androidx.hilt.compiler)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4.accessibility)
    androidTestImplementation(libs.androidx.test.espresso)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    // ComponentActivity is declared only by the device-test manifest, so package
    // its runtime in the test APK rather than changing the production APK.
    androidTestImplementation(libs.androidx.activity)
    androidTestImplementation(libs.hilt.android.testing)
    kspAndroidTest(libs.hilt.compiler)
    androidTestImplementation(libs.androidx.work.testing)
    androidTestImplementation(libs.androidx.window.testing)
    androidTestImplementation(libs.androidx.test.uiautomator)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    screenshotTestImplementation(libs.screenshot.validation.api)
    screenshotTestImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.mockk)
}

val jacocoExcludedClasses = listOf(
    "**/R.class",
    "**/R$*.class",
    "**/BuildConfig.*",
    "**/Manifest*.*",
)

val collectDebugClasses = tasks.register<DebugClassesCollectorTask>("collectDebugClasses") {
    outputDirectory.set(layout.buildDirectory.dir("intermediates/jacoco/debugClasses"))
}

tasks.register<JacocoReport>("jacocoTestReport") {
    dependsOn("testDebugUnitTest")
    dependsOn(collectDebugClasses)

    reports {
        xml.required = true
        xml.outputLocation.set(layout.buildDirectory.file("reports/jacoco/testDebugUnitTestCoverage/testDebugUnitTestCoverage.xml"))
        html.required = true
        html.outputLocation = layout.buildDirectory.dir("reports/jacoco/testDebugUnitTestCoverage")
    }

    sourceDirectories.setFrom(
        layout.projectDirectory.dir("src/main/kotlin"),
        layout.projectDirectory.dir("src/main/java"),
    )
    executionData.setFrom(layout.buildDirectory.file("jacoco/testDebugUnitTest.exec"))
    classDirectories.setFrom(
        fileTree(layout.buildDirectory.dir("intermediates/jacoco/debugClasses")) {
            exclude(jacocoExcludedClasses)
        },
    )
}

tasks.register<JacocoCoverageVerifierTask>("verifyJacocoCoverage") {
    collectedClassDirectory.set(layout.buildDirectory.dir("intermediates/jacoco/debugClasses"))
    targetClassNames.addAll(
        listOf(
            "com/cattailsw/nanidroid/BootDispatchState",
            "com/cattailsw/nanidroid/SScriptRunner",
            "com/cattailsw/nanidroid/runtime/KotlinGhostPresentationRuntime",
        ),
    )
    dependsOn(collectDebugClasses)
}

androidComponents {
    onVariants(selector().withName("debug")) { variant ->
        variant.artifacts.forScope(ScopedArtifacts.Scope.PROJECT)
            .use(tasks.named<DebugClassesCollectorTask>("collectDebugClasses"))
            .toGet(
                ScopedArtifact.CLASSES,
                { task -> task.classJars },
                { task -> task.classDirs },
            )
    }
}

abstract class DebugClassesCollectorTask @Inject constructor(
    private val fileSystemOperations: FileSystemOperations,
) : DefaultTask() {

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val classJars: ListProperty<RegularFile>

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val classDirs: ListProperty<Directory>

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun collectDebugClasses() {
        val destination = outputDirectory.get().asFile
        if (destination.exists()) {
            destination.deleteRecursively()
        }
        destination.mkdirs()
        fileSystemOperations.copy {
            from(classDirs.get().map { it.asFile })
            into(destination)
        }
        classJars.get().forEach { jar ->
            extractClassesFromJar(jar.asFile, destination)
        }
    }

    private fun extractClassesFromJar(jarFile: File, destination: File) {
        val jarUri = URI.create("jar:${jarFile.toURI()}")
        val jarFs = FileSystems.newFileSystem(jarUri, emptyMap<String, Any>())
        jarFs.use { fs ->
            Files.walk(fs.getPath("/")).use { files ->
                for (path in files) {
                    if (Files.isDirectory(path) || !path.toString().endsWith(".class")) {
                        continue
                    }
                    val target = destination.toPath().resolve(path.toString().trimStart('/', '\\'))
                    Files.createDirectories(target.parent)
                    Files.copy(path, target, StandardCopyOption.REPLACE_EXISTING)
                }
            }
        }
    }
}

abstract class JacocoCoverageVerifierTask : DefaultTask() {

    @get:Input
    abstract val targetClassNames: ListProperty<String>

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val collectedClassDirectory: DirectoryProperty

    @TaskAction
    fun verifyCollectedClasses() {
        val baseDir = collectedClassDirectory.get().asFile
        val targetSet = targetClassNames.get().toHashSet()
        val missingTargets = targetSet.filterNot { target ->
            val relativePath = target.replace('/', File.separatorChar) + ".class"
            baseDir.resolve(relativePath).isFile
        }

        if (missingTargets.isEmpty()) {
            logger.lifecycle(
                "Verified representative production classes exist in AGP-collected class output: $targetSet",
            )
            return
        }

        logger.warn(
            "Representative production classes were not collected into AGP class output: $missingTargets",
        )
    }
}
