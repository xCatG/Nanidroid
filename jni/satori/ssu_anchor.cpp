// Gives the Satori JNI bridge a stable address inside libssu.so. The bridge
// uses dladdr on it to create the legacy `ssu.dll` fallback name at runtime.
extern "C" void satori_ssu_anchor() {}
