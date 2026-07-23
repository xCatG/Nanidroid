import pathlib
import re
import subprocess
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

    def test_emulator_lane_is_opt_in_and_uses_separate_artifact_roots(self):
        project_root = pathlib.Path(__file__).resolve().parents[1]
        native_script = (
            project_root / "docker" / "emulator" / "build-native.sh"
        ).read_text(encoding="utf-8")
        apk_script = (
            project_root / "docker" / "emulator" / "build.sh"
        ).read_text(encoding="utf-8")
        gradle_build = (project_root / "build.gradle.kts").read_text(encoding="utf-8")

        self.assertIn('OUTPUT_ROOT="${OUTPUT_ROOT:-/out}"', native_script)
        self.assertIn('case "${BUILD_ROOT}" in', native_script)
        self.assertIn('/tmp/*)', native_script)
        self.assertIn('CMAKE_BUILD_ROOT="${BUILD_ROOT}/cmake-arm64-build"', native_script)
        self.assertIn("-DANDROID_ABI=arm64-v8a", native_script)
        self.assertIn("-DANDROID_PLATFORM=android-21", native_script)
        self.assertIn("-DANDROID_STL=gnustl_static", native_script)
        self.assertIn("assembleEmulator", apk_script)
        self.assertIn('create("emulator")', gradle_build)
        self.assertIn('dir("artifacts/emulator/native")', gradle_build)
        self.assertIn("docker/legacy/compose.yaml run --rm emulator-native", gradle_build)

        clear_native = native_script.index('rm -rf "${NATIVE_ROOT}"')
        configure = native_script.index("cmake \\")
        self.assertLess(clear_native, configure)

    def test_hosted_ci_builds_and_uploads_the_emulator_artifacts(self):
        project_root = pathlib.Path(__file__).resolve().parents[1]
        workflow = (
            project_root / ".github" / "workflows" / "legacy-build.yml"
        ).read_text(encoding="utf-8")
        normalized = " ".join(workflow.split())

        self.assertIn(
            "mkdir -p artifacts/legacy artifacts/gradle "
            "artifacts/emulator/native artifacts/emulator/apk",
            normalized,
        )
        self.assertIn(
            "docker compose -f docker/legacy/compose.yaml run --rm",
            normalized,
        )
        self.assertGreaterEqual(
            normalized.count('--user "$(id -u):$(id -g)"'), 2
        )
        self.assertIn("--env HOME=/tmp/nanidroid-emulator-home", normalized)
        self.assertIn("--env OUTPUT_ROOT=/out", normalized)
        self.assertIn("emulator-native -lc", normalized)
        self.assertIn(
            "exec bash /workspace/docker/emulator/build-native.sh", normalized
        )
        self.assertIn(
            "./docker/gradle/build.sh && ./docker/emulator/build.sh",
            normalized,
        )
        self.assertIn("path: artifacts/", workflow)

    def test_emulator_build_scripts_are_executable_in_the_git_index(self):
        project_root = pathlib.Path(__file__).resolve().parents[1]
        paths = [
            "docker/emulator/build-native.sh",
            "docker/emulator/build.sh",
        ]
        completed = subprocess.run(
            ["git", "ls-files", "--stage", "--", *paths],
            cwd=project_root,
            check=True,
            capture_output=True,
            text=True,
        )
        modes = {
            line.split(maxsplit=3)[3]: line.split(maxsplit=1)[0]
            for line in completed.stdout.splitlines()
        }

        self.assertEqual({path: "100755" for path in paths}, modes)

    def test_characterization_sources_are_the_exact_expected_set(self):
        project_root = pathlib.Path(__file__).resolve().parents[1]
        expected = {
            pathlib.PurePosixPath(
                "test/jvm/com/cattailsw/nanidroid/DescReaderCharacterizationTest.java"
            ),
            pathlib.PurePosixPath(
                "test/jvm/com/cattailsw/nanidroid/SakuraScriptCharacterizationTest.java"
            ),
            pathlib.PurePosixPath(
                "test/jvm/com/cattailsw/nanidroid/ShioriEnvelopeCharacterizationTest.java"
            ),
            pathlib.PurePosixPath(
                "test/jvm/com/cattailsw/nanidroid/SurfaceDefinitionCharacterizationTest.java"
            ),
            pathlib.PurePosixPath(
                "test/jvm/com/cattailsw/nanidroid/ViewServerLifecycleCharacterizationTest.java"
            ),
        }
        unit_test_roots = [
            root
            for root in (project_root / "src").iterdir()
            if root.is_dir() and root.name.startswith("test")
        ]
        unit_test_roots.append(project_root / "test" / "jvm")
        actual = {
            pathlib.PurePosixPath(path.relative_to(project_root).as_posix())
            for root in unit_test_roots
            if root.is_dir()
            for path in root.rglob("*")
            if path.suffix in {".java", ".kt"}
        }

        self.assertEqual(expected, actual)

    def test_active_nanidroid_view_server_calls_use_the_compatibility_boundary(self):
        project_root = pathlib.Path(__file__).resolve().parents[1]
        nanidroid = (
            project_root / "src" / "com" / "cattailsw" / "nanidroid" / "Nanidroid.java"
        ).read_text(encoding="utf-8")
        active_source = re.sub(r"/\*.*?\*/", "", nanidroid, flags=re.DOTALL)

        self.assertNotIn("ViewServer.get(", active_source)
        self.assertEqual(1, active_source.count("ViewServerLifecycle.onActivityCreated(this);"))
        self.assertEqual(1, active_source.count("ViewServerLifecycle.onActivityResumed(this);"))
        self.assertEqual(1, active_source.count("ViewServerLifecycle.onActivityDestroyed(this);"))

    def test_default_return_stub_guard_wires_every_app_unit_test_task(self):
        project_root = pathlib.Path(__file__).resolve().parents[1]
        gradle_build = (project_root / "build.gradle.kts").read_text(encoding="utf-8")

        self.assertIn("VerifyCharacterizationTestIsolation", gradle_build)
        self.assertIn("val missing = (expected - actual)", gradle_build)
        self.assertIn("val unexpected = (actual - expected)", gradle_build)

        normalized_gradle_build = " ".join(gradle_build.split())
        complete_unit_test_wiring = (
            'tasks.matching { it.name.startsWith("test") && '
            'it.name.endsWith("UnitTest") }.configureEach { '
            "dependsOn(verifyCharacterizationTestIsolation) }"
        )
        self.assertIn(complete_unit_test_wiring, normalized_gradle_build)


if __name__ == "__main__":
    unittest.main()
