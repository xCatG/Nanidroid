import unittest
from pathlib import Path


class KotlinGhostDomainContractTest(unittest.TestCase):
    def test_ghost_is_kotlin_and_preserves_java_subclass_hooks(self):
        root = Path(__file__).resolve().parents[1]
        self.assertFalse((root / "src/com/cattailsw/nanidroid/Ghost.java").exists())
        self.assertFalse((root / "legacy").exists())
        source = (root / "src/com/cattailsw/nanidroid/Ghost.kt").read_text(
            encoding="utf-8"
        )
        self.assertIn("open class Ghost", source)
        self.assertIn("protected open fun loadGhostInfo()", source)
        self.assertIn("protected open fun incrementCreateCount()", source)
        for method in (
            "open fun getCreateCount()",
            "open fun getGhostId()",
            "open fun getGhostName()",
            "open fun getSakuraName()",
            "open fun getKeroName()",
            "open fun getUsername()",
            "open fun doShioriEvent(event: String, ref: Array<String>?)",
        ):
            self.assertIn(method, source)
        self.assertIn("@JvmField protected var rootPath", source)
        self.assertIn("@JvmField protected var ghostDesc", source)
        self.assertIn("@JvmField protected var error", source)
        self.assertIn("fun doShioriEvent(event: String, ref: Array<String>?): ShioriResponse", source)


if __name__ == "__main__":
    unittest.main()
