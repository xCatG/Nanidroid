import unittest
from pathlib import Path


class KotlinShioriFactoryContractTest(unittest.TestCase):
    def setUp(self):
        self.root = Path(__file__).resolve().parents[1]
        self.runtime = (
            self.root / "src/main/kotlin/com/cattailsw/nanidroid/GhostRuntime.kt"
        ).read_text(encoding="utf-8")

    def test_static_factory_is_absent_and_runtime_owns_adapter_construction(self):
        factory_root = self.root / "src/main/kotlin/com/cattailsw/nanidroid"
        self.assertFalse((factory_root / "ShioriFactory.java").exists())
        self.assertFalse((factory_root / "ShioriFactory.kt").exists())
        self.assertIn("private fun createAdapter(prepared: PreparedGhost): Shiori", self.runtime)

        adapter_constructors = (
            "SatoriShiori(",
            "YayaShiori(",
            "Kawari(",
            "NanidroidShiori(",
            "NotSupportedShiori(",
        )
        occurrences = {}
        for path in sorted((self.root / "src/main/kotlin").rglob("*.kt")):
            source = path.read_text(encoding="utf-8")
            count = sum(source.count(constructor) for constructor in adapter_constructors)
            if count:
                occurrences[path.relative_to(self.root).as_posix()] = count
        self.assertEqual(
            {
                "src/main/kotlin/com/cattailsw/nanidroid/GhostRuntime.kt": 5,
                "src/main/kotlin/com/cattailsw/nanidroid/shiori/Kawari.kt": 1,
                "src/main/kotlin/com/cattailsw/nanidroid/shiori/NanidroidShiori.kt": 3,
                "src/main/kotlin/com/cattailsw/nanidroid/shiori/NotSupportedShiori.kt": 1,
                "src/main/kotlin/com/cattailsw/nanidroid/shiori/SatoriShiori.kt": 1,
                "src/main/kotlin/com/cattailsw/nanidroid/shiori/YayaShiori.kt": 1,
            },
            occurrences,
        )

    def test_runtime_routes_every_prepared_engine_to_its_exact_adapter(self):
        self.assertIn(
            "GhostEngine.Satori -> SatoriShiori(master, applicationContext)",
            self.runtime,
        )
        self.assertIn(
            "GhostEngine.Yaya -> YayaShiori(master, applicationContext)",
            self.runtime,
        )
        self.assertIn("GhostEngine.Kawari -> Kawari(master)", self.runtime)
        self.assertIn(
            "GhostEngine.Nanidroid -> NanidroidShiori(applicationContext, prepared.nanidroidContent)",
            self.runtime,
        )
        self.assertIn(
            "GhostEngine.Unsupported -> NotSupportedShiori(applicationContext)",
            self.runtime,
        )
        self.assertNotIn("SatoriPosixShiori(", self.runtime)

    def test_mainline_has_no_archived_factory(self):
        self.assertFalse((self.root / "legacy").exists())

    def test_instrumentation_never_constructs_a_native_adapter(self):
        instrumentation = self.root / "src/androidTest"
        for path in sorted(instrumentation.rglob("*.kt")):
            source = path.read_text(encoding="utf-8")
            for constructor in (
                "SatoriShiori(",
                "YayaShiori(",
                "Kawari(",
                "NanidroidShiori(",
                "NotSupportedShiori(",
            ):
                self.assertNotIn(constructor, source, path.relative_to(self.root).as_posix())

    def test_real_engine_tests_skip_only_when_the_run_sentinel_is_absent(self):
        lifecycle = (
            self.root
            / "src/androidTest/java/com/cattailsw/nanidroid/ShioriLifecycleInstrumentationTest.kt"
        ).read_text(encoding="utf-8")
        transition = (
            self.root
            / "src/androidTest/java/com/cattailsw/nanidroid/CrossEngineRuntimeInstrumentationTest.kt"
        ).read_text(encoding="utf-8")

        support = lifecycle.split("internal object RealEngineAuditSupport", 1)[1]
        signature = "fun assumeAuditConfigured(arguments: Bundle)"
        self.assertIn(signature, support)
        assumption = support.split(signature, 1)[1].split(
            "fun requireRunId(arguments: Bundle)", 1
        )[0]
        self.assertIn("Assume.assumeTrue(", assumption)
        self.assertIn('arguments.containsKey("runtimeAuditRunId")', assumption)
        self.assertNotIn("getString", assumption)
        self.assertNotIn("isNullOr", assumption)
        run_id_validation = support.split(
            "fun requireRunId(arguments: Bundle)", 1
        )[1].split("fun requireRunRoot", 1)[0]
        self.assertIn(
            'requiredArgument(arguments, "runtimeAuditRunId")',
            run_id_validation,
        )

        call = "RealEngineAuditSupport.assumeAuditConfigured(arguments)"
        validation = "RealEngineAuditSupport.requireRunId(arguments)"
        for source in (lifecycle, transition):
            self.assertEqual(1, source.count(call))
            self.assertLess(source.index(call), source.index(validation))


if __name__ == "__main__":
    unittest.main()
