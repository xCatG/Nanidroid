### Best practices and security alignment update: Android 36 component, network, and service boundaries

* **Improvement Description:** Makes the externally reachable archive entry point HTTPS-only and validates it on both cold and warm activity delivery; removes unsafe `file:` handoff and permissive Apache HTTP/TLS behavior; makes the downloader an explicit private `dataSync` foreground service.
* **Priority Level:** High
* **Alignment Action:** Target SDK is now 36. The launcher/deep-link activity is explicitly exported, private components are explicitly non-exported, downloads use immutable explicit `PendingIntent`s, and shared `/sdcard/nar` installation is deliberately disabled until the SAF picker migration.

* **Review follow-up:** Foreground command completion is centralized in `finishForegroundWork(startId)` and used by both download and update success/failure paths. Registered start IDs are tracked under a lock: a completed job stops its own start ID, while `stopForeground(true)` runs only when no registered work remains. Cold `ACTION_VIEW` is dispatched once, after initialization; `onNewIntent` replaces the retained intent then uses the same validator.

#### Files modified

* `AndroidManifest.xml`
* `build.gradle.kts`
* `src/com/cattailsw/nanidroid/{IncomingNarIntent,Nanidroid,NanidroidService,NetworkUtil,SSTPBottleSensor,ShioriResponse,ShioriProtocolVersion}.java`
* `res/values/strings.xml`
* `docker/gradle/build.sh` and APK-contract tools/tests

#### Deferred boundary

This PR does not attempt to make a caller-controlled local pathname safe. The historical shared-storage picker is unavailable with a precise user message; a later SAF/document-picker PR must supply a `content:` URI, persistable grant handling, and installer streaming. Download-complete notifications no longer imply that an untrusted file path will be installed. The frozen JVM characterizations compile but cannot execute faithfully on the host because they require Android framework behavior and native libraries; PR45 owns device/emulator execution.

The frozen Ant/API-15 reference lane builds from a disposable copied manifest. It strips only `android:foregroundServiceType="dataSync"`, which API-15 `aapt` cannot parse; the production Gradle/API-36 manifest keeps that required declaration.

#### Implementation diff

```diff
--- a/AndroidManifest.xml
+++ b/AndroidManifest.xml
@@
-  <uses-permission android:name="android.permission.WRITE_EXTERNAL_STORAGE" />
+  <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
+  <uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC" />
@@
-      android:launchMode="singleInstance"
+      android:launchMode="singleTop"
+      android:exported="true"
@@
-    <service android:name=".NanidroidService" android:exported="false" />
+    <service android:name=".NanidroidService" android:exported="false"
+      android:foregroundServiceType="dataSync" />
```

```diff
--- /dev/null
+++ b/src/com/cattailsw/nanidroid/IncomingNarIntent.java
@@
+    static boolean isApprovedDownload(Intent intent) {
+        return intent != null
+                && Intent.ACTION_VIEW.equals(intent.getAction())
+                && isApprovedDownload(intent.getData());
+    }
+
+    static boolean isApprovedDownload(Uri uri) {
+        if (uri == null || !"https".equalsIgnoreCase(uri.getScheme())) return false;
+        ...
+        return lowerPath.endsWith(".nar") || lowerPath.endsWith(".zip");
+    }
--- a/src/com/cattailsw/nanidroid/Nanidroid.java
+++ b/src/com/cattailsw/nanidroid/Nanidroid.java
@@
-    public void onNewIntent(Intent intent) { handleIntent(intent); }
+    public void onNewIntent(Intent intent) {
+        super.onNewIntent(intent);
+        setIntent(intent);
+        handleIncomingIntent(intent);
+    }
```

```diff
--- a/src/com/cattailsw/nanidroid/NanidroidService.java
+++ b/src/com/cattailsw/nanidroid/NanidroidService.java
@@
-    PendingIntent pi = PendingIntent.getActivity(..., 0);
-    ni.setData(Uri.fromFile(new File(result))).putExtra("DL_PKG",0);
+    int flags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
+    PendingIntent pi = PendingIntent.getActivity(..., flags);
+    startForeground(FOREGROUND_NOTIFICATION_ID, createForegroundNotification());
--- a/src/com/cattailsw/nanidroid/util/NetworkUtil.java
+++ b/src/com/cattailsw/nanidroid/util/NetworkUtil.java
@@
-import org.apache.http...;
+import javax.net.ssl.HttpsURLConnection;
+// requireHttps rejects non-HTTPS URLs and default platform TLS verification is retained.
```

The complete, reviewable unified diff is the PR diff; the excerpts above cover every security boundary changed by this update.

#### Testing and verification

1. `python -m unittest tools.test_target36_security_contract ...` — 33 focused security, artifact-contract, and parser tests passed.
2. `gradlew --no-daemon compileDebugJavaWithJavac` — passed with API 36 SDK.
3. `gradlew --no-daemon assembleDebug bundleDebug` — passed; produced debug APK and AAB.
4. `testEmulatorUnitTest` was deliberately not treated as a pass: the legacy JVM characterizations require real Android/JNI behavior and fail under host Android stubs. Device/emulator proof remains PR45's gate.
