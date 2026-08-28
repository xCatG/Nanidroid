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
        source = (self.root / "src/main/kotlin/com/cattailsw/nanidroid/shiori/YayaShiori.kt").read_text(
            encoding="utf-8"
        )
        self.assertGreaterEqual(source.count("nativeTransportCharset()"), 2)
        self.assertIn("Charset.forName", source)
        self.assertNotIn("toByteArray(SHIFT_JIS)", source)

    def test_kawari_load_rejects_an_existing_owner_without_disposing_it(self):
        source = (self.root / "jni/kawari8/kawari_jni.cpp").read_text(encoding="utf-8")
        load_body = source.rsplit("Java_com_cattailsw_nanidroid_shiori_Kawari_nativeLoad", 1)[1].split(
            "Java_com_cattailsw_nanidroid_shiori_Kawari_nativeUnload", 1
        )[0]
        self.assertIn("if (h != 0)", load_body)
        self.assertIn("return -1", load_body)
        self.assertNotIn("DisposeInstance((int)h)", load_body)

    def test_kawari_adapter_rejects_a_missing_or_failed_main_dictionary(self):
        source = (self.root / "jni/kawari8/shiori/kawari_shiori.cpp").read_text(
            encoding="utf-8", errors="replace"
        )
        load_body = source.split("bool TKawariShioriAdapter::Load", 1)[1].split(
            "bool TKawariShioriAdapter::Unload", 1
        )[0]
        failure_check = 'if (!Engine.LoadKawariDict(datapath+"kawarirc.kis"))'
        self.assertIn(failure_check, load_body)
        failed_branch = load_body.split(failure_check, 1)[1].split("}", 1)[0]
        self.assertIn("return(false);", failed_branch)
        self.assertLess(load_body.index(failure_check), load_body.index("initialized=true"))

        factory_body = source.split("TKawariShioriFactory::CreateInstance", 1)[1].split(
            "TKawariShioriFactory::DisposeInstance", 1
        )[0]
        failed_instance = factory_body.split("if (!instance->Load(datapath))", 1)[1].split(
            "}", 1
        )[0]
        self.assertIn("delete instance;", failed_instance)
        self.assertIn("return 0;", failed_instance)
        self.assertLess(factory_body.index("return 0;"), factory_body.index("list.push_back(instance)"))

        jni = (self.root / "jni/kawari8/kawari_jni.cpp").read_text(encoding="utf-8")
        native_load = jni.rsplit(
            "Java_com_cattailsw_nanidroid_shiori_Kawari_nativeLoad", 1
        )[1].split("Java_com_cattailsw_nanidroid_shiori_Kawari_nativeUnload", 1)[0]
        self.assertIn("h = TKawariShioriFactory::GetFactory().CreateInstance(directory);", native_load)
        self.assertIn("return h != 0 ? 1 : 0;", native_load)

    def test_native_lifecycle_methods_return_explicit_statuses(self):
        satori = (self.root / "jni/satori/satori_jni.cpp").read_text(encoding="utf-8")
        yaya = (self.root / "jni/yaya/yaya_jni.cpp").read_text(encoding="utf-8")
        kawari = (self.root / "jni/kawari8/kawari_jni.cpp").read_text(encoding="utf-8")
        for source in (satori, yaya):
            self.assertIn('"(Ljava/lang/String;Ljava/lang/String;)I"', source)
            self.assertIn('"()Z"', source)
            self.assertIn("return -1", source)
        self.assertIn("JNIEXPORT jint JNICALL Java_com_cattailsw_nanidroid_shiori_Kawari_nativeLoad", kawari)
        self.assertIn("JNIEXPORT jboolean JNICALL Java_com_cattailsw_nanidroid_shiori_Kawari_nativeUnload", kawari)

    def test_satori_and_yaya_preserve_an_existing_owner(self):
        for relative, loaded_flag in (
            ("jni/satori/satori_jni.cpp", "satoriLoaded"),
            ("jni/yaya/yaya_jni.cpp", "gYayaLoaded"),
        ):
            source = (self.root / relative).read_text(encoding="utf-8")
            load_body = source.split("nativeLoad", 1)[1].split("nativeRequest", 1)[0]
            owner_branch = load_body.split(f"if ({loaded_flag})", 1)[1].split("GetStringUTFChars", 1)[0]
            self.assertIn("return -1", owner_branch)
            self.assertNotIn("unload()", owner_branch)

    def test_native_requests_reject_unloaded_state(self):
        satori = (self.root / "jni/satori/satori_jni.cpp").read_text(encoding="utf-8")
        yaya = (self.root / "jni/yaya/yaya_jni.cpp").read_text(encoding="utf-8")
        kawari = (self.root / "jni/kawari8/kawari_jni.cpp").read_text(encoding="utf-8")
        self.assertIn('"Satori is not loaded"', satori)
        self.assertIn('"YAYA is not loaded"', yaya)
        self.assertIn('"YAYA is not loaded"', yaya.split("nativeTransportCharset", 1)[1])
        self.assertIn('"Kawari 8 is not loaded"', kawari)

    def test_kawari_unload_checks_dispose_and_clears_the_handle(self):
        source = (self.root / "jni/kawari8/kawari_jni.cpp").read_text(encoding="utf-8")
        unload_body = source.rsplit("Java_com_cattailsw_nanidroid_shiori_Kawari_nativeUnload", 1)[1]
        self.assertIn("if (h == 0) return JNI_TRUE", unload_body)
        self.assertIn("if (!TKawariShioriFactory::GetFactory().DisposeInstance((int)h)) return JNI_FALSE", unload_body)
        self.assertIn("h = 0", unload_body)

    def test_satori_and_yaya_check_owner_before_fallible_path_conversion(self):
        for relative, lock_type, loaded_flag in (
            ("jni/satori/satori_jni.cpp", "SatoriLock lock;", "satoriLoaded"),
            ("jni/yaya/yaya_jni.cpp", "YayaLock lock;", "gYayaLoaded"),
        ):
            source = (self.root / relative).read_text(encoding="utf-8")
            load_body = source.split("nativeLoad", 1)[1].split("nativeRequest", 1)[0]
            self.assertLess(load_body.index(lock_type), load_body.index("GetStringUTFChars"))
            self.assertLess(load_body.index(f"if ({loaded_flag})"), load_body.index("GetStringUTFChars"))
            self.assertLess(load_body.index(lock_type), load_body.index("load(copy"))

    def test_kawari_serializes_handle_access(self):
        source = (self.root / "jni/kawari8/kawari_jni.cpp").read_text(encoding="utf-8")
        self.assertIn("pthread_mutex_t kawari_mutex", source)
        self.assertEqual(source.count("KawariLock lock;"), 3)

    def test_yaya_input_allocation_failure_is_not_fabricated_as_an_empty_response(self):
        source = (self.root / "jni/yaya/yaya_jni.cpp").read_text(encoding="utf-8")
        request_body = source.split("jbyteArray nativeRequest", 1)[1].split("jboolean nativeUnload", 1)[0]
        allocation_failure = request_body.split("if (input == NULL)", 1)[1].split("GetByteArrayRegion", 1)[0]
        self.assertIn('throwIllegalState(env, "Could not allocate YAYA request buffer")', allocation_failure)
        self.assertIn("return NULL", allocation_failure)

    def test_yaya_engine_empty_responses_release_bridge_buffers(self):
        source = (self.root / "jni/yaya/yaya_jni.cpp").read_text(encoding="utf-8")
        self.assertIn("free(result);\n        free(input);\n        return env->NewByteArray(0);", source)

    def test_satori_and_ssu_bind_their_own_host_symbols(self):
        source = (self.root / "jni/CMakeLists.txt").read_text(encoding="utf-8")
        self.assertIn('target_link_options(satoriya PRIVATE "-Wl,-Bsymbolic")', source)
        self.assertIn('target_link_options(ssu PRIVATE "-Wl,-Bsymbolic")', source)
        self.assertIn('target_link_options(yaya PRIVATE "-Wl,-Bsymbolic")', source)

    def test_yaya_maps_engine_pseudo_charsets_to_android_transports(self):
        source = (self.root / "src/main/kotlin/com/cattailsw/nanidroid/shiori/YayaShiori.kt").read_text(encoding="utf-8")
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

    def test_android_ssu_fallback_uses_the_linked_soname_without_symlinks(self):
        satori_jni = (self.root / "jni/satori/satori_jni.cpp").read_text(encoding="utf-8")
        yaya_jni = (self.root / "jni/yaya/yaya_jni.cpp").read_text(encoding="utf-8")
        satori_plugins = (self.root / "jni/satori/shiori_plugin.cpp").read_text(
            encoding="utf-8", errors="replace"
        )
        yaya_library = (self.root / "jni/yaya/lib1.cpp").read_text(
            encoding="utf-8", errors="replace"
        )
        self.assertNotIn("symlink(", satori_jni)
        self.assertNotIn("symlink(", yaya_jni)
        self.assertIn("satori_ssu_anchor();", satori_jni)
        self.assertIn("satori_ssu_anchor();", yaya_jni)
        self.assertIn('"libssu.so"', satori_plugins)
        self.assertIn('"libssu.so"', yaya_library)
        self.assertIn('filename = "ssu"', yaya_library)
    def test_ssu_exports_the_yaya_saori_compatibility_abi(self):
        source = (self.root / "jni/satori/ssu.cpp").read_text(encoding="utf-8", errors="replace")
        self.assertIn('extern "C" long ssu_saori_load', source)
        self.assertIn('extern "C" int ssu_saori_unload', source)
        self.assertIn('extern "C" char* ssu_saori_request', source)
    def test_yaya_saori_fallback_is_not_configured_through_process_environment(self):
        jni = (self.root / "jni/yaya/yaya_jni.cpp").read_text(encoding="utf-8")
        library = (self.root / "jni/yaya/lib1.cpp").read_text(
            encoding="utf-8", errors="replace"
        )
        kotlin = (self.root / "src/main/kotlin/com/cattailsw/nanidroid/shiori/YayaShiori.kt").read_text(
            encoding="utf-8"
        )
        runtime = (self.root / "src/main/kotlin/com/cattailsw/nanidroid/GhostRuntime.kt").read_text(
            encoding="utf-8"
        )
        self.assertNotIn("getenv(\"SAORI_FALLBACK", library)
        self.assertIn("yaya_configure_posix_saori_fallback", jni)
        self.assertIn("private val cacheDirectory = context?.codeCacheDir?.absolutePath ?: path", kotlin)
        self.assertIn("nativeLoad(path, cacheDirectory)", kotlin)
        self.assertIn("GhostEngine.Yaya -> YayaShiori(master, applicationContext)", runtime)

    def test_yaya_onload_checks_the_host_class_before_initializing_charsets(self):
        jni = (self.root / "jni/yaya/yaya_jni.cpp").read_text(encoding="utf-8")
        self.assertLess(jni.index("if (type == NULL) return JNI_ERR"), jni.index("android_charset_initialize(env)"))

    def test_yaya_android_charset_bridge_preserves_utf16_surrogates(self):
        bridge = (self.root / "jni/yaya/android_charset.cpp").read_text(encoding="utf-8")
        decoder = bridge.split("yaya::char_t* android_charset_to_utf16", 1)[1]
        self.assertIn("output.push_back(static_cast<yaya::char_t>(chars[i]));", decoder)
        self.assertNotIn("0x10000 + ((value - 0xd800)", decoder)
    def test_yaya_caches_android_charset_objects(self):
        bridge = (self.root / "jni/yaya/android_charset.cpp").read_text(encoding="utf-8")
        self.assertIn("gCharsetCache", bridge)
        self.assertIn("NewGlobalRef", bridge)
        self.assertNotIn("jobject charset_for(JNIEnv* env, int charset)", bridge)

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
