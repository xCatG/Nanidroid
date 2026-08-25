import pathlib
import re
import unittest


ROOT = pathlib.Path(__file__).resolve().parents[1]
PRODUCTION_ROOT = ROOT / "src/main/kotlin"
APPLICATION = "src/main/kotlin/com/cattailsw/nanidroid/CatTailApplication.kt"
RUNTIME_CONSTRUCTION = re.compile(r"(?<!class )\bGhostRuntime\s*\(")


class GhostRuntimeCompositionRootTest(unittest.TestCase):
    def test_application_is_the_only_production_runtime_creator(self) -> None:
        creators = {}
        for path in sorted(PRODUCTION_ROOT.rglob("*.kt")):
            count = len(RUNTIME_CONSTRUCTION.findall(path.read_text(encoding="utf-8")))
            if count:
                creators[str(path.relative_to(ROOT)).replace("\\", "/")] = count

        self.assertEqual({APPLICATION: 1}, creators)


if __name__ == "__main__":
    unittest.main()
