import pathlib
import re
import tomllib
import unittest
import xml.etree.ElementTree as ET


ROOT = pathlib.Path(__file__).resolve().parents[1]

LEGACY_RUNTIME_PATHS = (
    "src/main/kotlin/com/cattailsw/nanidroid/durable/DurableOperation.kt",
    "src/main/kotlin/com/cattailsw/nanidroid/durable/DurableOperationAttentionCoordinator.kt",
    "src/main/kotlin/com/cattailsw/nanidroid/durable/DurableOperationAttentionNotification.kt",
    "src/main/kotlin/com/cattailsw/nanidroid/durable/DurableOperationStore.kt",
    "src/main/kotlin/com/cattailsw/nanidroid/durable/DurableOperationSupervisor.kt",
    "src/main/kotlin/com/cattailsw/nanidroid/durable/SharedDurableOperationSupervisor.kt",
    "src/main/kotlin/com/cattailsw/nanidroid/durable/SharedPreferencesDurableOperationStore.kt",
    "src/main/kotlin/com/cattailsw/nanidroid/compose/durable/DurableStoreRecoveryPrompt.kt",
    "src/main/kotlin/com/cattailsw/nanidroid/compose/durable/StalledOperationPrompt.kt",
    "src/main/kotlin/com/cattailsw/nanidroid/install/DownloadManagerProgressObserver.kt",
    "src/main/kotlin/com/cattailsw/nanidroid/install/InstallNarWorker.kt",
    "src/main/kotlin/com/cattailsw/nanidroid/install/NarDownload.kt",
    "src/main/kotlin/com/cattailsw/nanidroid/install/NarDownloadReceiver.kt",
    "src/main/kotlin/com/cattailsw/nanidroid/install/NarDownloadRecoveryReceiver.kt",
    "src/main/kotlin/com/cattailsw/nanidroid/install/NarDownloadRepository.kt",
    "src/main/kotlin/com/cattailsw/nanidroid/install/NarDownloadStore.kt",
    "src/main/kotlin/com/cattailsw/nanidroid/install/NarInstallProgressReporter.kt",
    "src/main/kotlin/com/cattailsw/nanidroid/install/NarLocalArchiveStager.kt",
    "src/main/kotlin/com/cattailsw/nanidroid/install/StageLocalNarWorker.kt",
    "src/test/java/com/cattailsw/nanidroid/durable/DurableOperationAttentionCoordinatorTest.kt",
    "src/test/java/com/cattailsw/nanidroid/durable/DurableOperationSupervisorTest.kt",
    "src/test/java/com/cattailsw/nanidroid/durable/SharedDurableOperationSupervisorTest.kt",
    "src/test/java/com/cattailsw/nanidroid/durable/SharedPreferencesDurableOperationStoreTest.kt",
    "src/test/java/com/cattailsw/nanidroid/install/NarDownloadRepositoryTest.kt",
    "src/test/java/com/cattailsw/nanidroid/install/NarDownloadStoreTest.kt",
    "src/test/java/com/cattailsw/nanidroid/install/NarInstallProgressReporterTest.kt",
    "src/test/java/com/cattailsw/nanidroid/install/NarLocalArchiveStagerTest.kt",
    "src/androidTest/java/com/cattailsw/nanidroid/durable/DurableOperationAttentionInstrumentationTest.kt",
    "src/androidTest/java/com/cattailsw/nanidroid/install/InstallNarWorkerCancellationTest.kt",
)

OBSOLETE_DURABLE_STRINGS = (
    "durable_attention_channel_name",
    "durable_attention_channel_description",
    "durable_attention_title",
    "durable_operation_remote_nar",
    "durable_operation_local_nar",
    "durable_operation_nar_install",
    "durable_action_keep_waiting",
    "durable_action_stop",
    "durable_action_retry_stop",
    "durable_phase_downloading",
    "durable_phase_copying",
    "durable_phase_installing",
    "durable_phase_stopping",
    "durable_diagnostics_label",
    "durable_diagnostic_cancel_dispatch_failed",
    "durable_diagnostic_stopping_delayed",
    "durable_store_recovery_title",
    "durable_store_recovery_message",
    "durable_store_recovery_confirm",
    "durable_store_recovery_failed",
)

OBSOLETE_DURABLE_PATHS = (
    "src/main/res/xml/backup_rules.xml",
    "src/main/res/xml/data_extraction_rules.xml",
    "docs/modernization/durable-operation-transition-table.md",
    "src/test/java/com/cattailsw/nanidroid/DurableBackupRulesTest.kt",
    "src/main/res/drawable-hdpi-v11/notification.png",
    "src/main/res/drawable-ja/notification.png",
    "src/main/res/drawable-ja-hdpi-v11/notification.png",
    "src/main/res/drawable-ja-mdpi-v11/notification.png",
    "src/main/res/drawable-ja-xhdpi-v11/notification.png",
    "src/main/res/drawable-mdpi/notification.png",
    "src/main/res/drawable-mdpi-v11/notification.png",
    "src/main/res/drawable-xhdpi-v11/notification.png",
)

PLATFORM_STACK_PATHS = (
    "src/main/kotlin/com/cattailsw/nanidroid/di/PlatformClockModule.kt",
    "src/androidTest/java/com/cattailsw/nanidroid/NanidroidTestRunner.kt",
    "src/androidTest/java/com/cattailsw/nanidroid/DependencyInjectionSmokeTest.kt",
)

LIFECYCLE_INSTRUMENTATION_TEST_METHODS = {
    "recreatingAttachedSessionPreservesApplicationRuntimeGhostAndGeneration",
    "recreatingWhileInitialPreparationIsBlockedJoinsOneRuntimeOperation",
    "recreatingAfterOutgoingUnloadJoinsOneReplacementOperation",
    "concurrentApplicationReadsReturnOneRuntimeAndRunner",
    "startupRecoverySettlesBeforeTestCoordinatorReplacement",
    "sameProcessRecreationRestoresTheExactPickerOwnerWithoutRelaunching",
    "concurrentActivityReconciliationCannotCancelTheLiveOwnerResult",
    "recreatingDuringCopyingAndInstallingKeepsOneImportAttempt",
    "installedPrimaryWaitsForReplacementGhostMgrAndCleanupRetryRefreshesOnce",
    "deadProcessPickerTokenCannotOpenItsReturnedUriOrCreateAnActivityDialog",
    "pausingActivityStopsClockWithoutReplacingRuntimeOrNativeSession",
}

STRING_RESOURCE_PATHS = (
    "src/main/res/values/strings.xml",
    "src/main/res/values-ja/strings.xml",
    "src/main/res/values-zh-rTW/strings.xml",
)

ANDROID_NAMESPACE = "http://schemas.android.com/apk/res/android"

FORBIDDEN_PRODUCTION_KOTLIN_FRAGMENTS = (
    "androidx.work",
    "androidx.hilt",
    "dagger.hilt",
    "NarDownloadRepository",
    "SharedDurableOperationSupervisor",
    "DurableOperation",
    "InstallNarWorker",
    "StageLocalNarWorker",
    "NarLocalArchiveStager",
    "DownloadManagerProgressObserver",
)


class LegacyArchiveRuntimeAbsenceTest(unittest.TestCase):
    def read(self, relative_path: str) -> str:
        return (ROOT / relative_path).read_text(encoding="utf-8")

    def test_foreground_and_runtime_boundaries_are_retained(self) -> None:
        coordinator = self.read(
            "src/main/kotlin/com/cattailsw/nanidroid/install/"
            "ForegroundNarImportCoordinator.kt"
        )
        installer = self.read(
            "src/main/kotlin/com/cattailsw/nanidroid/install/"
            "NarTransactionalInstaller.kt"
        )
        runtime_clock = self.read(
            "src/main/kotlin/com/cattailsw/nanidroid/runtime/MonotonicClock.kt"
        )
        runner = self.read(
            "src/main/kotlin/com/cattailsw/nanidroid/SScriptRunner.kt"
        )

        self.assertIn("class ForegroundNarImportCoordinator(", coordinator)
        self.assertIn("class NarTransactionalInstaller", installer)
        self.assertIn("class SScriptRunner", runner)
        self.assertIn("fun interface MonotonicClock", runtime_clock)
        self.assertIn("fun nowMillis(): Long", runtime_clock)
        self.assertIn(
            "import com.cattailsw.nanidroid.runtime.MonotonicClock",
            runner,
        )
        self.assertNotIn("com.cattailsw.nanidroid.di.MonotonicClock", runner)

    def test_legacy_runtime_paths_are_absent(self) -> None:
        present = [path for path in LEGACY_RUNTIME_PATHS if (ROOT / path).exists()]
        self.assertEqual([], present)

    def test_durable_string_resources_are_absent(self) -> None:
        for relative_path in STRING_RESOURCE_PATHS:
            declared_names = {
                element.attrib["name"]
                for element in ET.parse(ROOT / relative_path).getroot().findall("string")
            }
            with self.subTest(resource_file=relative_path):
                self.assertTrue(declared_names.isdisjoint(OBSOLETE_DURABLE_STRINGS))
                self.assertFalse(
                    any(name.startswith("durable_") for name in declared_names)
                )

    def test_obsolete_durable_artifacts_are_absent(self) -> None:
        present = [path for path in OBSOLETE_DURABLE_PATHS if (ROOT / path).exists()]
        self.assertEqual([], present)

    def test_platform_stack_files_are_absent(self) -> None:
        present = [path for path in PLATFORM_STACK_PATHS if (ROOT / path).exists()]
        self.assertEqual([], present)

    def test_build_has_no_platform_stack_plugins_or_dependencies(self) -> None:
        build = self.read("build.gradle.kts")
        forbidden_fragments = (
            "libs.plugins.hilt",
            "libs.plugins.ksp",
            "libs.work.runtime",
            "libs.androidx.work.runtime",
            "libs.androidx.hilt.work",
            "libs.hilt.android",
            "ksp(",
            "kspAndroidTest(",
            "libs.hilt.android.testing",
            "libs.work.testing",
            "libs.androidx.work.testing",
            "libs.androidx.hilt.compiler",
        )

        for fragment in forbidden_fragments:
            with self.subTest(fragment=fragment):
                self.assertNotIn(fragment, build)

    def test_catalog_has_no_platform_stack_aliases(self) -> None:
        catalog = tomllib.loads(self.read("gradle/libs.versions.toml"))
        forbidden_aliases = {
            "versions": {"work", "hilt", "androidx-hilt", "ksp"},
            "libraries": {
                "androidx-work-runtime",
                "androidx-work-testing",
                "androidx-hilt-work",
                "androidx-hilt-compiler",
                "hilt-android",
                "hilt-compiler",
                "hilt-android-testing",
            },
            "plugins": {"hilt", "ksp"},
        }

        for section, aliases in forbidden_aliases.items():
            with self.subTest(section=section):
                self.assertTrue(aliases.isdisjoint(catalog.get(section, {})))

    def test_application_has_plain_startup_recovery_only(self) -> None:
        application = self.read(
            "src/main/kotlin/com/cattailsw/nanidroid/CatTailApplication.kt"
        )
        forbidden_fragments = (
            "androidx.hilt",
            "androidx.work",
            "dagger.hilt",
            "HiltAndroidApp",
            "HiltWorkerFactory",
            "Configuration.Provider",
            "workManagerConfiguration",
            "workerFactory",
            "@Inject",
            "javax.inject",
        )

        for fragment in forbidden_fragments:
            with self.subTest(fragment=fragment):
                self.assertNotIn(fragment, application)
        self.assertIn("ForegroundNarImportCoordinator.get(this)", application)
        self.assertIn("class CatTailApplication : Application()", application)

    def test_activity_has_no_hilt_wiring(self) -> None:
        activity = self.read(
            "src/main/kotlin/com/cattailsw/nanidroid/Nanidroid.kt"
        )
        self.assertNotIn("AndroidEntryPoint", activity)
        self.assertNotIn("dagger.hilt", activity)
        self.assertIn("class Nanidroid : ComponentActivity()", activity)

    def test_production_kotlin_has_no_legacy_platform_references(self) -> None:
        kotlin_root = ROOT / "src/main/kotlin"
        kotlin_files = sorted(kotlin_root.rglob("*.kt"))
        self.assertNotEqual([], kotlin_files)
        violations = {
            str(path.relative_to(ROOT)): [
                fragment
                for fragment in FORBIDDEN_PRODUCTION_KOTLIN_FRAGMENTS
                if fragment in path.read_text(encoding="utf-8")
            ]
            for path in kotlin_files
        }
        self.assertEqual(
            {},
            {path: fragments for path, fragments in violations.items() if fragments},
        )

    def test_lifecycle_instrumentation_uses_no_hilt_and_keeps_eleven_proofs(self) -> None:
        lifecycle_test = self.read(
            "src/androidTest/java/com/cattailsw/nanidroid/"
            "NanidroidLifecycleInstrumentationTest.kt"
        )
        forbidden_fragments = (
            "dagger.hilt",
            "HiltAndroidRule",
            "HiltAndroidTest",
            "org.junit.Rule",
            "@get:Rule",
        )

        for fragment in forbidden_fragments:
            with self.subTest(fragment=fragment):
                self.assertNotIn(fragment, lifecycle_test)
        test_methods = set(re.findall(r"@Test\s+fun\s+(\w+)\s*\(", lifecycle_test))
        self.assertEqual(LIFECYCLE_INSTRUMENTATION_TEST_METHODS, test_methods)

    def test_build_uses_standard_instrumentation_runner(self) -> None:
        build = self.read("build.gradle.kts")
        self.assertIn(
            'testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"',
            build,
        )

    def test_corpus_harness_uses_standard_instrumentation_runner(self) -> None:
        harness = self.read("scripts/run-nar-corpus-audit.ps1")
        self.assertIn(
            '$instrumentationRunner = "$testPackage/androidx.test.runner.AndroidJUnitRunner"',
            harness,
        )
        self.assertNotIn("NanidroidTestRunner", harness)

    def test_source_manifest_has_no_platform_stack_components(self) -> None:
        manifest_source = self.read("src/main/AndroidManifest.xml")
        manifest = ET.fromstring(manifest_source)
        forbidden_fragments = (
            "xmlns:tools",
            "tools:",
            "androidx.work",
            "android.permission.FOREGROUND_SERVICE",
            "WorkManagerInitializer",
            "InitializationProvider",
        )

        for fragment in forbidden_fragments:
            with self.subTest(fragment=fragment):
                self.assertNotIn(fragment, manifest_source)
        self.assertEqual([], manifest.findall("uses-permission"))
        self.assertEqual([], manifest.findall(".//provider"))
        application = manifest.find("application")
        self.assertIsNotNone(application)
        self.assertEqual(
            "CatTailApplication",
            application.get(f"{{{ANDROID_NAMESPACE}}}name"),
        )
        activities = application.findall("activity")
        self.assertEqual(1, len(activities))
        self.assertEqual(
            "Nanidroid",
            activities[0].get(f"{{{ANDROID_NAMESPACE}}}name"),
        )

    def test_manifest_has_no_obsolete_backup_rules(self) -> None:
        application = ET.parse(ROOT / "src/main/AndroidManifest.xml").getroot().find(
            "application"
        )
        self.assertIsNotNone(application)
        self.assertNotIn(f"{{{ANDROID_NAMESPACE}}}fullBackupContent", application.attrib)
        self.assertNotIn(f"{{{ANDROID_NAMESPACE}}}dataExtractionRules", application.attrib)


if __name__ == "__main__":
    unittest.main()
