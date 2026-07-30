import unittest
from pathlib import Path


class NativeShioriContractTest(unittest.TestCase):
    def setUp(self):
        self.root = Path(__file__).resolve().parents[1]

    def test_yaya_registers_natives_with_c_linkage(self):
        source = (self.root / "jni/yaya/yaya_jni.cpp").read_text(encoding="utf-8")
        self.assertIn('extern "C" JNIEXPORT jint JNI_OnLoad', source)
        self.assertIn('"nativeTransportCharset"', source)

    def test_yaya_bridge_uses_the_engine_transport_charset(self):
        source = (self.root / "src/com/cattailsw/nanidroid/shiori/YayaShiori.kt").read_text(
            encoding="utf-8"
        )
        self.assertEqual(source.count("nativeTransportCharset()"), 2)
        self.assertIn("Charset.forName", source)
        self.assertNotIn("toByteArray(SHIFT_JIS)", source)

    def test_kawari_disposes_a_previous_global_handle_before_loading(self):
        source = (self.root / "jni/kawari8/kawari_jni.cpp").read_text(encoding="utf-8")
        load_body = source.split("Java_com_cattailsw_nanidroid_shiori_Kawari_load", 1)[1].split(
            "Java_com_cattailsw_nanidroid_shiori_Kawari_unload", 1
        )[0]
        self.assertIn("if (h != 0)", load_body)
        self.assertIn("DisposeInstance((int)h)", load_body)

    def test_yaya_empty_responses_release_bridge_buffers(self):
        source = (self.root / "jni/yaya/yaya_jni.cpp").read_text(encoding="utf-8")
        self.assertIn("free(result);\n        free(input);\n        return env->NewByteArray(0);", source)

    def test_satori_and_ssu_bind_their_own_host_symbols(self):
        source = (self.root / "jni/CMakeLists.txt").read_text(encoding="utf-8")
        self.assertIn('target_link_options(satoriya PRIVATE "-Wl,-Bsymbolic")', source)
        self.assertIn('target_link_options(ssu PRIVATE "-Wl,-Bsymbolic")', source)

    def test_vendor_tree_excludes_binary_and_ide_artifacts(self):
        for relative in (
            "jni/satori/lib",
            "jni/satori/lib.exe",
            "jni/satori/satori.so",
            "jni/kawari8/vc_kawari/vc_kawari.suo",
            "jni/kawari8/vc_kawari/vc_kosui.suo",
        ):
            self.assertFalse((self.root / relative).exists(), relative)


if __name__ == "__main__":
    unittest.main()
