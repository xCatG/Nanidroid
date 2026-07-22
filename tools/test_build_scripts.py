import pathlib
import unittest


class BuildScriptContractTest(unittest.TestCase):
    def test_disposable_copy_excludes_gradle_working_state(self):
        project_root = pathlib.Path(__file__).resolve().parents[1]
        build_script = (project_root / "docker" / "legacy" / "build.sh").read_text(
            encoding="utf-8"
        )

        self.assertIn("--exclude /.gradle/", build_script)

    def test_gradle_build_relocates_the_project_cache(self):
        project_root = pathlib.Path(__file__).resolve().parents[1]
        build_script = (project_root / "docker" / "gradle" / "build.sh").read_text(
            encoding="utf-8"
        )

        self.assertIn('--project-cache-dir "${PROJECT_CACHE_ROOT}"', build_script)

    def test_default_return_stubs_have_an_exact_characterization_allowlist(self):
        project_root = pathlib.Path(__file__).resolve().parents[1]
        gradle_build = (project_root / "build.gradle.kts").read_text(encoding="utf-8")

        self.assertIn("VerifyCharacterizationTestIsolation", gradle_build)
        self.assertIn("DescReaderCharacterizationTest.java", gradle_build)
        self.assertIn("SakuraScriptCharacterizationTest.java", gradle_build)
        self.assertIn("filterNot { it.canonicalFile in allowed }", gradle_build)
        self.assertIn("dependsOn(verifyCharacterizationTestIsolation)", gradle_build)


if __name__ == "__main__":
    unittest.main()
