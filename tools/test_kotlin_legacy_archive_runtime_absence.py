import pathlib
import unittest


ROOT = pathlib.Path(__file__).resolve().parents[1]


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


if __name__ == "__main__":
    unittest.main()
