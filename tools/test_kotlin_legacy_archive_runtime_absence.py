import pathlib
import unittest


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


if __name__ == "__main__":
    unittest.main()
