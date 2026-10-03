# Scaffold verification — 2026-09-23

Command: `./gradlew.bat :app:assembleDebug` from the standalone repository.
Result: BUILD SUCCESSFUL in 2m 6s; 36 tasks executed; exit 0.
This verifies only the unmodified CLI template (com.example.nanidroid, compile/target 36), not the planned product or API-37 configuration. No unit tests, instrumentation tests or device execution were run.

Generated toolchain: AGP 9.0.1, Kotlin 2.3.20, Gradle 9.1.0, Compose BOM 2026.03.01; JDK toolchain 17.
Android CLI 1.0.16261425, launcher 0.7.15225349.
SDK: C:/Users/yenchi/AppData/Local/Android/Sdk.
Build warning: SDK parser supports XML v3 but encountered v4; nonfatal in this build.

Existing emulator inventory (listed only, none started or modified):
- Medium_Phone_API_36.1
- Nanidroid_API_37
- Nanidroid_ARM64_API_31
- Nanidroid_PR393_AOSP_ATD_API_35
- Nanidroid_X86_ARM64_BRIDGE_API_36_1
- Small_Phone_API31

Do not assume any existing emulator is disposable or lacks user data. Follow the plan's device isolation check.

Generated APK: app/build/outputs/apk/debug/app-debug.apk. SHA-256: 1bfd1a4fa942f877bc9430f85aa47fa0ce2b50ca90be9debdcc3b069686538e5
