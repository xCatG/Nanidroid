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
        self.assertGreaterEqual(source.count("nativeTransportCharset()"), 2)
        self.assertIn("Charset.forName", source)
        self.assertNotIn("toByteArray(SHIFT_JIS)", source)

    def test_kawari_disposes_a_previous_global_handle_before_loading(self):
        source = (self.root / "jni/kawari8/kawari_jni.cpp").read_text(encoding="utf-8")
        load_body = source.rsplit("Java_com_cattailsw_nanidroid_shiori_Kawari_load", 1)[1].split(
            "Java_com_cattailsw_nanidroid_shiori_Kawari_unload", 1
        )[0]
        self.assertIn("if (h != 0)", load_body)
        self.assertIn("DisposeInstance((int)h)", load_body)

    def test_kawari_serializes_handle_access(self):
        source = (self.root / "jni/kawari8/kawari_jni.cpp").read_text(encoding="utf-8")
        self.assertIn("pthread_mutex_t kawari_mutex", source)
        self.assertEqual(source.count("KawariLock lock;"), 3)

    def test_yaya_empty_responses_release_bridge_buffers(self):
        source = (self.root / "jni/yaya/yaya_jni.cpp").read_text(encoding="utf-8")
        self.assertIn("free(result);\n        free(input);\n        return env->NewByteArray(0);", source)

    def test_satori_and_ssu_bind_their_own_host_symbols(self):
        source = (self.root / "jni/CMakeLists.txt").read_text(encoding="utf-8")
        self.assertIn('target_link_options(satoriya PRIVATE "-Wl,-Bsymbolic")', source)
        self.assertIn('target_link_options(ssu PRIVATE "-Wl,-Bsymbolic")', source)

    def test_ghost_switch_unloads_before_starting_replacement(self):
        source = (self.root / "src/com/cattailsw/nanidroid/SScriptRunner.kt").read_text(encoding="utf-8")
        stop_body = source.split("@Synchronized fun stop()", 1)[1].split("private fun reset", 1)[0]
        self.assertLess(stop_body.index("g!!.unload()"), stop_body.index("it.ghostSwitchScriptComplete()"))

    def test_ghost_switch_pauses_clock_until_replacement_is_bound(self):
        source = (self.root / "src/com/cattailsw/nanidroid/Nanidroid.kt").read_text(encoding="utf-8")
        switch_body = source.split("fun switchGhost(nextId: String)", 1)[1].split("fun ghostSwitchStep2()", 1)[0]
        self.assertIn("runner!!.stopClock()", switch_body)
        replacement_body = source.rsplit("runner!!.setGhost(ghost)", 1)[1]
        self.assertTrue(replacement_body.lstrip().startswith("runner!!.startClock()"))

    def test_yaya_maps_engine_pseudo_charsets_to_android_transports(self):
        source = (self.root / "src/com/cattailsw/nanidroid/shiori/YayaShiori.kt").read_text(encoding="utf-8")
        self.assertIn("Charset.defaultCharset()", source)
        self.assertIn("Charsets.ISO_8859_1", source)

    def test_yaya_digest_words_are_fixed_width_on_64_bit_abis(self):
        sha1_header = (self.root / "jni/yaya/sha1.h").read_text(encoding="utf-8")
        global_header = (self.root / "jni/yaya/global.h").read_text(encoding="utf-8")
        self.assertIn("#include <stdint.h>", sha1_header)
        self.assertNotIn("typedef unsigned long uint32_t", sha1_header)
        self.assertIn("typedef uint32_t UINT4", global_header)

    def test_yaya_uses_android_charset_bridge_for_legacy_transport(self):
        bridge = (self.root / "jni/yaya/android_charset.cpp").read_text(encoding="utf-8")
        source = (self.root / "jni/yaya/ccct.cpp").read_text(encoding="utf-8", errors="replace")
        jni = (self.root / "jni/yaya/yaya_jni.cpp").read_text(encoding="utf-8")
        self.assertIn("java/nio/charset/Charset", bridge)
        self.assertIn("Shift_JIS", bridge)
        self.assertIn("ISO-2022-JP", bridge)
        self.assertIn("android_charset_to_utf16", source)
        self.assertIn("android_utf16_to_charset", source)
        self.assertIn("android_charset_initialize", jni)
        bridge_call = source.index("android_utf16_to_charset")
        bom_strip = source.index("pUcsStr[0] == static_cast<yaya::char_t>(0xfeff)")
        self.assertLess(bom_strip, bridge_call)

    def test_satori_saori_fallback_is_instance_scoped(self):
        jni = (self.root / "jni/satori/satori_jni.cpp").read_text(encoding="utf-8")
        plugins = (self.root / "jni/satori/shiori_plugin.cpp").read_text(
            encoding="utf-8", errors="replace"
        )
        header = (self.root / "jni/satori/shiori_plugin.h").read_text(
            encoding="utf-8", errors="replace"
        )
        self.assertNotIn("setenv(", jni)
        self.assertNotIn("getenv(", plugins)
        self.assertIn("configure_posix_fallback", header)
        self.assertLess(jni.index("configure_posix_saori_fallback"), jni.index("if (!load(copy, length))"))
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
