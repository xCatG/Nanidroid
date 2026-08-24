import pathlib
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
)

STRING_RESOURCE_PATHS = (
    "src/main/res/values/strings.xml",
    "src/main/res/values-ja/strings.xml",
    "src/main/res/values-zh-rTW/strings.xml",
)

ANDROID_NAMESPACE = "http://schemas.android.com/apk/res/android"


class LegacyArchiveRuntimeAbsenceTest(unittest.TestCase):
    def read(self, relative_path: str) -> str:
        return (ROOT / relative_path).read_text(encoding="utf-8")

    def test_monotonic_clock_has_neutral_runtime_ownership(self) -> None:
        runtime_clock = self.read(
            "src/main/kotlin/com/cattailsw/nanidroid/runtime/MonotonicClock.kt"
        )
        platform_module = self.read(
            "src/main/kotlin/com/cattailsw/nanidroid/di/PlatformClockModule.kt"
        )
        runner = self.read(
            "src/main/kotlin/com/cattailsw/nanidroid/SScriptRunner.kt"
        )

        self.assertIn("fun interface MonotonicClock", runtime_clock)
        self.assertIn("fun nowMillis(): Long", runtime_clock)
        self.assertNotIn("fun interface MonotonicClock", platform_module)
        self.assertIn(
            "typealias MonotonicClock = com.cattailsw.nanidroid.runtime.MonotonicClock",
            platform_module,
        )
        self.assertIn("com.cattailsw.nanidroid.runtime.MonotonicClock", runner)
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

    def test_manifest_has_no_obsolete_backup_rules(self) -> None:
        application = ET.parse(ROOT / "src/main/AndroidManifest.xml").getroot().find(
            "application"
        )
        self.assertIsNotNone(application)
        self.assertNotIn(f"{{{ANDROID_NAMESPACE}}}fullBackupContent", application.attrib)
        self.assertNotIn(f"{{{ANDROID_NAMESPACE}}}dataExtractionRules", application.attrib)


if __name__ == "__main__":
    unittest.main()
