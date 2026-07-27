# PR F5 — Kotlin GhostMgr

`GhostMgr` is now Kotlin while retaining the Java-visible method names and
overloads used by `Nanidroid.java` and the legacy dialog classes. It owns ghost
discovery, selection, preferences, and the D9b3 transactional install result;
no JNI or rendering behavior changes in this slice.

The frozen Ant reference lane remains Java-only. Its disposable Docker build
explicitly overlays `legacy/src/.../GhostMgr.java` after copying the checkout;
Gradle never compiles that overlay. This preserves the historical reference APK
without compromising the modern production source set.

The migration intentionally keeps the Activity and dialogs on their existing
Java API surface while moving a cohesive non-NDK service to Kotlin. It replaces
nullable list handling with Kotlin nullability but preserves legacy outcomes:
unknown ids return `-1`/`null` where they did before, and direct indexed access
continues to surface programmer errors rather than silently changing behavior.

`tools/test_kotlin_ghost_manager_contract.py` pins the source-language change,
the Java-call surface, and transactional installer ownership. The existing
NARFS contracts were updated to inspect `GhostMgr.kt` while keeping their JNI
hash pinning. Mixed Kotlin/Java compilation, the transactional installer JVM
test, `assembleDevice`, and an API 36.1 emulator launch passed; the installed
ghost tree remained present after the APK replacement.
