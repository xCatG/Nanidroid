# Frozen legacy reference project

`reference-project/` is the complete 327-file `legacy/` project tree from Git
commit `027c971` (`Move legacy code to legacy/ subdirectory`): its manifest,
Ant metadata, assets, resources, Java source, JNI source, historical libraries,
and tests are immutable reference inputs. It is not an active Android source set.

`reference-third-party/GoogleAdMobAdsSdk-6.0.1.jar` is the exact dependency
removed by `0390a86`, recovered from `0390a86^:libs/GoogleAdMobAdsSdk-6.0.1.jar`.
Its SHA-256 is
`378f6757e9d881af1369377da431651e12a3c08fa8e565096e268dacacb491af`.
The frozen Java project imports `com.google.ads.*`; this binary is retained as
historical provenance, not replaced with an Ads stub.

`tools/verify_legacy_reference_snapshot.py` validates the full project manifest
and this sole declared binary before Docker clears or publishes generated
artifacts. `docker/legacy/build.sh` copies only these immutable inputs into its
Ant root. Current JNI/NarFS verification runs separately in
`docker/legacy/build-modern-native.sh`, with a distinct disposable root.
