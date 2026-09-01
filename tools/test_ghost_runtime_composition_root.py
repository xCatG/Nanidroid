import pathlib
import re
import unittest


ROOT = pathlib.Path(__file__).resolve().parents[1]
PRODUCTION_ROOT = ROOT / "src/main/kotlin"
SOURCE_ROOT = ROOT / "src"
APPLICATION = "src/main/kotlin/com/cattailsw/nanidroid/CatTailApplication.kt"
RUNTIME_CONSTRUCTION = re.compile(r"(?<!class )\bGhostRuntime\s*\(")


def read(relative_path: str) -> str:
    return (ROOT / relative_path).read_text(encoding="utf-8")


def files_containing_any(*needles: str) -> set[str]:
    matches = set()
    for path in sorted(PRODUCTION_ROOT.rglob("*.kt")):
        source = path.read_text(encoding="utf-8")
        if any(
            re.search(rf"(?<!class )\b{re.escape(needle)}", source)
            for needle in needles
        ):
            matches.add(str(path.relative_to(ROOT)).replace("\\", "/"))
    return matches


class GhostRuntimeCompositionRootTest(unittest.TestCase):
    def test_application_is_the_only_production_runtime_creator(self) -> None:
        creators = {}
        for path in sorted(PRODUCTION_ROOT.rglob("*.kt")):
            if path.name == "GhostRuntime.kt":
                continue
            count = len(RUNTIME_CONSTRUCTION.findall(path.read_text(encoding="utf-8")))
            if count:
                creators[str(path.relative_to(ROOT)).replace("\\", "/")] = count

        self.assertEqual({APPLICATION: 1}, creators)

    def test_runtime_is_the_only_production_native_session_authority(self) -> None:
        constructors = (
            "SatoriShiori(",
            "YayaShiori(",
            "Kawari(",
            "NanidroidShiori(",
            "NotSupportedShiori(",
        )
        occurrences = {}
        for path in sorted(PRODUCTION_ROOT.rglob("*.kt")):
            source = path.read_text(encoding="utf-8")
            count = sum(source.count(constructor) for constructor in constructors)
            if count:
                occurrences[str(path.relative_to(ROOT)).replace("\\", "/")] = count
        self.assertEqual(
            {
                "src/main/kotlin/com/cattailsw/nanidroid/GhostRuntime.kt": 5,
                "src/main/kotlin/com/cattailsw/nanidroid/shiori/Kawari.kt": 1,
                "src/main/kotlin/com/cattailsw/nanidroid/shiori/NanidroidShiori.kt": 2,
                "src/main/kotlin/com/cattailsw/nanidroid/shiori/NotSupportedShiori.kt": 1,
                "src/main/kotlin/com/cattailsw/nanidroid/shiori/SatoriShiori.kt": 1,
                "src/main/kotlin/com/cattailsw/nanidroid/shiori/YayaShiori.kt": 1,
            },
            occurrences,
        )
        ghost = read("src/main/kotlin/com/cattailsw/nanidroid/Ghost.kt")
        self.assertNotIn("Shiori", ghost)
        self.assertNotIn("doShioriEvent(", ghost)
        self.assertNotIn("requestRaw(", ghost)

    def test_transitional_session_authorities_and_activity_continuation_are_absent(self) -> None:
        package = "src/main/kotlin/com/cattailsw/nanidroid"
        for obsolete_file in (
            "GhostSessionCoordinator",
            "ShioriFactory",
            "InfoOnlyGhost",
            "DirList",
        ):
            self.assertFalse((ROOT / package / f"{obsolete_file}.kt").exists())
            self.assertFalse((ROOT / package / f"{obsolete_file}.java").exists())

        all_source = "\n".join(
            path.read_text(encoding="utf-8")
            for path in sorted(SOURCE_ROOT.rglob("*.kt"))
        )
        for obsolete in (
            "GhostSessionCoordinator",
            "ReservedGhost",
            "GhostConstructionReservation",
            "attachReservedGhost",
            "abandonReservedGhost",
            "setGhostToRunner",
        ):
            self.assertNotIn(obsolete, all_source)
        activity = read(f"{package}/Nanidroid.kt")
        self.assertNotIn("nextGhostId", activity)
        self.assertNotIn("ghostSwitchStep2", activity)

    def test_runtime_constructs_the_only_production_runner(self) -> None:
        self.assertEqual(
            {"src/main/kotlin/com/cattailsw/nanidroid/GhostRuntime.kt"},
            files_containing_any("SScriptRunner("),
        )


if __name__ == "__main__":
    unittest.main()
