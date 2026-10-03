# Offline notice inventory

Task 4 inventory for the development build. `NoticeCatalog` reads each file from `assets/notices/` in the APK. SHA-256 values below identify the source file or artifact and the displayed UTF-8 asset. The native text is preserved verbatim after decoding Satori Shift_JIS and the Kawari C string escapes. This is a source inventory, not a legal-compliance determination.

| Displayed asset | Source and extraction | Source SHA-256 | Asset SHA-256 |
| --- | --- | --- | --- |
| androidx-apache.txt | <Gradle cache>/androidx.compose.material3/material3-android/1.4.0/6064b108969ab5557a0c19c7e026554f377cecf5/material3.aar (META-INF/androidx/compose/material3/material3/LICENSE.txt) | 3a37e8b36df3822fe1e6059f0f9fafda8800388860477624ac1b9422c418a36e | 809fa1ed21450f59827d1e9aec720bbc4b687434fa22283c6cb5dd82a47ab9c0 |
| bundled-ghost.txt | app/src/main/assets/nanidroid.zip (readme.txt UTF-8 text), followed by a clearly labeled app-supplied URI; original readme text is verbatim | 2ebf24a2be8255011c5e004459a58dd1317eb7b12a55adcbe6b8b2977c89dc2d | b0bc48808a0f7da047082f6fcd370ef62e5114d8fd6cd2b18ebf417dc556d407 |
| commons-codec-LICENSE.txt | <Gradle cache>/commons-codec/commons-codec/1.19.0/8c0dbe3ae883fceda9b50a6c76e745e548073388/commons-codec-1.19.0.jar (META-INF/LICENSE.txt) | 5c3881e4f556855e9c532927ee0c9dfde94cc66760d5805c031a59887070af5f | b1d2870f1a00e4d7f56576e5f0870cba109e041f8af66866cf5b499478e654e7 |
| commons-codec-NOTICE.txt | <Gradle cache>/commons-codec/commons-codec/1.19.0/8c0dbe3ae883fceda9b50a6c76e745e548073388/commons-codec-1.19.0.jar (META-INF/NOTICE.txt) | 5c3881e4f556855e9c532927ee0c9dfde94cc66760d5805c031a59887070af5f | b64933ee1d36d14659156223a2604edadb60bffdd465d5368ff422d7689db5fb |
| commons-compress-LICENSE.txt | <Gradle cache>/org.apache.commons/commons-compress/1.28.0/e482f2c7a88dac3c497e96aa420b6a769f59c8d7/commons-compress-1.28.0.jar (META-INF/LICENSE.txt) | e1522945218456f3649a39bc4afd70ce4bd466221519dba7d378f2141a4642ca | 51b88fd3e9e24edcdce1dd145a4fca2dcd12911407f00b06dfa6ff600f9733c1 |
| commons-compress-NOTICE.txt | <Gradle cache>/org.apache.commons/commons-compress/1.28.0/e482f2c7a88dac3c497e96aa420b6a769f59c8d7/commons-compress-1.28.0.jar (META-INF/NOTICE.txt) | e1522945218456f3649a39bc4afd70ce4bd466221519dba7d378f2141a4642ca | 0d98967f0ab328af8d9d536aa9a5c684b5dd665657245f6ee6b5915affe811c0 |
| commons-io-LICENSE.txt | <Gradle cache>/commons-io/commons-io/2.20.0/36f3474daec2849c149e877614e7f979b2082cd2/commons-io-2.20.0.jar (META-INF/LICENSE.txt) | df90bba0fe3cb586b7f164e78fe8f8f4da3f2dd5c27fa645f888100ccc25dd72 | c1d1a38c99c48ccad170890ade6066e2e240bb6ce59861d4eb7f672eddd2a4c6 |
| commons-io-NOTICE.txt | <Gradle cache>/commons-io/commons-io/2.20.0/36f3474daec2849c149e877614e7f979b2082cd2/commons-io-2.20.0.jar (META-INF/NOTICE.txt) | df90bba0fe3cb586b7f164e78fe8f8f4da3f2dd5c27fa645f888100ccc25dd72 | b54b0db6c617273efff79ba193bb01b05eadcddfff55c24063b38b2acef714c3 |
| commons-lang3-LICENSE.txt | <Gradle cache>/org.apache.commons/commons-lang3/3.18.0/fb14946f0e39748a6571de0635acbe44e7885491/commons-lang3-3.18.0.jar (META-INF/LICENSE.txt) | 4eeeae8d20c078abb64b015ec158add383ac581571cddc45c68f0c9ae0230720 | b1d2870f1a00e4d7f56576e5f0870cba109e041f8af66866cf5b499478e654e7 |
| commons-lang3-NOTICE.txt | <Gradle cache>/org.apache.commons/commons-lang3/3.18.0/fb14946f0e39748a6571de0635acbe44e7885491/commons-lang3-3.18.0.jar (META-INF/NOTICE.txt) | 4eeeae8d20c078abb64b015ec158add383ac581571cddc45c68f0c9ae0230720 | 64bc8696af3f6c770521412b85d3a733fa44b2ef5b0f4bd739e0c4d02eca492a |
| kawari-mt19937.txt | app/src/main/jni/kawari8/misc/mt19937ar.h (opening comment) | 216c9b9b657c29eb7a43b437c12f06fd72182ba86d0c38d28f473a4ab5aae098 | f0f898f662b2a86105893a4ff4113690ee727f68ec37b13d3f9fba7cfe2b00ee |
| kawari.txt | app/src/main/jni/kawari8/libkawari/kawari_version.h (KAWARI_CORE_LICENSE) | 9b83318b987eaecb5268be9077e8c5523f8f9edbf2d256fbc7feda0dd040823b | b6423d75ba37b6a6fd65e4db3ba4f61f387798ed27018ca8d04e2246227e9689 |
| kotlin-stdlib-boost.txt | [Kotlin v2.3.20 Boost license text](https://raw.githubusercontent.com/JetBrains/kotlin/v2.3.20/license/third_party/boost_LICENSE.txt) | 8d8291caf1cee26d23acf3eb67c9f9a2d58f1c681b16a4fbe8cbfb9e3c0b5a9b | 8d8291caf1cee26d23acf3eb67c9f9a2d58f1c681b16a4fbe8cbfb9e3c0b5a9b |
| kotlin-stdlib-copyright.txt | [Kotlin v2.3.20 COPYRIGHT.txt](https://raw.githubusercontent.com/JetBrains/kotlin/v2.3.20/license/COPYRIGHT.txt) | 8f1a1700adb5490604c6094fb137bf73f6c28d3ee554b58f3ff8ef975a79672d | 8f1a1700adb5490604c6094fb137bf73f6c28d3ee554b58f3ff8ef975a79672d |
| kotlin-stdlib-gwt.txt | [Kotlin v2.3.20 GWT license text](https://raw.githubusercontent.com/JetBrains/kotlin/v2.3.20/license/third_party/gwt_license.txt); [Guava license text](https://raw.githubusercontent.com/JetBrains/kotlin/v2.3.20/license/third_party/guava_license.txt) is byte-identical | cfc7749b96f63bd31c3c42b5c471bf756814053e847c10f3eb003417bc523d30 | cfc7749b96f63bd31c3c42b5c471bf756814053e847c10f3eb003417bc523d30 |
| kotlin-stdlib-threetenbp.txt | [Kotlin v2.3.20 ThreeTenBP license text](https://raw.githubusercontent.com/JetBrains/kotlin/v2.3.20/license/third_party/threetenbp_license.txt) | d1bc53b493a3ab387b42717ed5c4b1976a5048996f81154278100bff86d39331 | d1bc53b493a3ab387b42717ed5c4b1976a5048996f81154278100bff86d39331 |
| satori.txt | app/src/main/jni/satori_license.txt (Shift_JIS text) | f07679e7d288eda3d03f0444db29fd5250de9a9231b03c7657d665826265cd74 | 17e5b2dd5923c43df285035cdfc149b04dd237949a5e205e686c7c5d2ce55731 |
| yaya-mt19937.txt | app/src/main/jni/yaya/mt19937ar.h (opening comment) | e40eb505bd359848b560a21b97606be6811c484dfb6a9ed535e8adce37461a63 | 09597eed7f396f3fdb88194ecdaca48dd8f3f67a23fd4364bde7ec7085cd6a6c |
| yaya.txt | app/src/main/jni/yaya/LICENSE (complete file) | 835cb88da811df138ad7fbadac695471999143dd751517bfa349c8acb1d09bc4 | 835cb88da811df138ad7fbadac695471999143dd751517bfa349c8acb1d09bc4 |

## Scope and gaps

The shipped native libraries are `satoriya`, `ssu`, `kawari8`, and `yaya` for arm64-v8a and x86_64, selected in `app/src/main/jni/CMakeLists.txt`. The retained tree supplies Satori `satori_license.txt`, Kawari `KAWARI_CORE_LICENSE`, YAYA `LICENSE`, and separate Mersenne Twister notices for Kawari and YAYA. SSU has no distinct notice in the retained tree, but the Satori distribution makefile builds both `libsatori` and `libssu` and packages a single `../satori_license.txt` alongside all Satori and SSU source files (`app/src/main/jni/satori/makefile.posix`, SHA-256 `bfc2e4331a7ec2515a6a5907b6f01008e87572ef4c32199c11f0a99313ab028e`). The SSU source calls itself a Satori-bundled utility (`app/src/main/jni/satori/ssu.cpp`, SHA-256 `8408e7367263dc2151f39e6f4aab878c08d967a6f44f868a9c5d554dc949e3a0`). This is source evidence that the displayed Satori text covers the same distribution, without inventing a separate SSU license.

The bundled `nanidroid.zip` readme attributes CatTail Software LLC, Android Robot/Google, and CatG Studio. It names Creative Commons 3.0 Attribution without a port, jurisdiction or license URI. The original readme attribution is displayed verbatim, followed by an explicitly app-supplied reference URI, `https://creativecommons.org/licenses/by/3.0/`, to the canonical Unported license. [CC BY 3.0 Unported legal code §4(a)](https://creativecommons.org/licenses/by/3.0/legalcode.en) permits providing a copy **or URI** of the license with copies of the work; that is why the full code is not bundled. The readme's unspecified 3.0 port remains an attribution ambiguity; this inventory does not assert legal compliance. `inputs.json` pins the zip SHA-256 and native transfer manifest.

The Commons JARs each contain their own `META-INF/LICENSE.txt` and `META-INF/NOTICE.txt`; both are copied into assets even though Gradle packaging excludes `/META-INF/{AL2.0,LGPL2.1}`. Of the resolved AndroidX/Compose code artifacts, 49 embedded `LICENSE.txt` with the same SHA-256 `809fa1ed21450f59827d1e9aec720bbc4b687434fa22283c6cb5dd82a47ab9c0` as the displayed `androidx-apache.txt`; exact Google Maven POMs for the other 18 code artifacts each declare Apache License 2.0. The remaining AndroidX graph entries are BOM, metadata or platform coordinates without separate cached code artifacts. The seven no-embedded-license non-AndroidX JARs are covered by exact POM or parent-POM evidence in the table below. No absent text has been fabricated.

| Resolved shipped artifacts | Exact primary source evidence | Displayed coverage |
| --- | --- | --- |
| `org.jetbrains.kotlin:kotlin-stdlib:2.3.20` | [exact Maven POM](https://repo.maven.apache.org/maven2/org/jetbrains/kotlin/kotlin-stdlib/2.3.20/kotlin-stdlib-2.3.20.pom) declares Apache 2.0; [tagged upstream license inventory](https://github.com/JetBrains/kotlin/blob/v2.3.20/license/README.md) identifies JVM stdlib GWT/Guava-derived Apache, ThreeTenBP BSD, and Boost MathJVM text. Jar contains the corresponding collections, unsigned, time and math classes. | Apache text plus exact tagged Kotlin copyright, GWT/Guava, ThreeTenBP and Boost texts above. Only JVM stdlib paths are included, not compiler/JS/native notices. |
| `org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0`, `kotlinx-coroutines-core-jvm:1.9.0`; `kotlinx-serialization-core-jvm:1.7.3` | [coroutines exact POM](https://repo.maven.apache.org/maven2/org/jetbrains/kotlinx/kotlinx-coroutines-core-jvm/1.9.0/kotlinx-coroutines-core-jvm-1.9.0.pom), [serialization exact POM](https://repo.maven.apache.org/maven2/org/jetbrains/kotlinx/kotlinx-serialization-core-jvm/1.7.3/kotlinx-serialization-core-jvm-1.7.3.pom), and tagged [coroutines](https://github.com/Kotlin/kotlinx.coroutines/blob/1.9.0/LICENSE.txt)/[serialization](https://github.com/Kotlin/kotlinx.serialization/blob/v1.7.3/LICENSE.txt) project license texts declare Apache 2.0. | Displayed `androidx-apache.txt` Apache text. Android adapter carries no separate embedded notice. |
| `org.jetbrains:annotations:23.0.0`; `org.jspecify:jspecify:1.0.0` | [annotations exact POM](https://repo.maven.apache.org/maven2/org/jetbrains/annotations/23.0.0/annotations-23.0.0.pom) and [JSpecify exact POM](https://repo.maven.apache.org/maven2/org/jspecify/jspecify/1.0.0/jspecify-1.0.0.pom) declare Apache 2.0; [JSpecify tagged LICENSE](https://github.com/jspecify/jspecify/blob/v1.0.0/LICENSE) agrees. | Displayed `androidx-apache.txt` Apache text. No separate embedded notice in resolved JARs. |
| `com.google.guava:listenablefuture:1.0` | [exact POM](https://repo.maven.apache.org/maven2/com/google/guava/listenablefuture/1.0/listenablefuture-1.0.pom) inherits [guava-parent:26.0-android POM](https://repo.maven.apache.org/maven2/com/google/guava/guava-parent/26.0-android/guava-parent-26.0-android.pom), which declares Apache 2.0. Inspected JAR contains `ListenableFuture.class`. | Displayed `androidx-apache.txt` Apache text; Kotlin's separate Guava-derived stdlib text is also mapped above. |

The downloaded exact POM SHA-256 values (in row order: stdlib, coroutines, serialization, annotations, JSpecify, listenablefuture, Guava parent) are `c83c02d9a7e2e954730491c33c2f1dc03c27662e27b566ec2b44d2d018639b98`, `19c4889941b3aa098bd57cc64f02f9adacc654d571a12a956de4b5bb148c6499`, `734f5f749208dd0bef3d98caa594cf9222afdf0fee5b6843347a8fe64e240427`, `c9490f655132328df2cfbcfdf743f53fc3916d6c1d10437175a6ca6e3a67771c`, `cdab929a3b95211f43d2090c5e2d0dfe8465960e378bc32b35841dab324433a6`, `53873caf26bc1ed8a567ea6c939ab2aaa3f47a5e32d5cade95ddf5080d23238a`, and `f8698ab46ca996ce889c1afc8ca4f25eb8ac6b034dc898d4583742360016cc04`. These establish project license coverage for the resolved code artifacts; the inventory is not a full legal audit of derivative work or all source-level attribution.

## Resolved release runtime graph

The graph comes from `:app:dependencies --configuration releaseRuntimeClasspath --offline` on 2026-09-28. It includes platform/BOM and metadata coordinates as well as packaged code; debug and test-only dependencies are excluded.

```text
androidx.activity:activity-compose:1.13.0
androidx.activity:activity-ktx:1.13.0
androidx.activity:activity:1.13.0
androidx.annotation:annotation-experimental:1.4.1
androidx.annotation:annotation-jvm:1.9.1
androidx.annotation:annotation:1.9.1
androidx.arch.core:core-common:2.2.0
androidx.arch.core:core-runtime:2.2.0
androidx.autofill:autofill:1.0.0
androidx.collection:collection-jvm:1.5.0
androidx.collection:collection-ktx:1.5.0
androidx.collection:collection:1.5.0
androidx.compose:compose-bom:2026.03.01
androidx.compose.animation:animation-android:1.10.6
androidx.compose.animation:animation-core-android:1.10.6
androidx.compose.animation:animation-core:1.10.6
androidx.compose.animation:animation:1.10.6
androidx.compose.foundation:foundation-android:1.10.6
androidx.compose.foundation:foundation-layout-android:1.10.6
androidx.compose.foundation:foundation-layout:1.10.6
androidx.compose.foundation:foundation:1.10.6
androidx.compose.material:material-ripple-android:1.10.6
androidx.compose.material:material-ripple:1.10.6
androidx.compose.material3:material3-android:1.4.0
androidx.compose.material3:material3:1.4.0
androidx.compose.runtime:runtime-android:1.10.6
androidx.compose.runtime:runtime-annotation-android:1.10.6
androidx.compose.runtime:runtime-annotation:1.10.6
androidx.compose.runtime:runtime-retain-android:1.10.6
androidx.compose.runtime:runtime-retain:1.10.6
androidx.compose.runtime:runtime-saveable-android:1.10.6
androidx.compose.runtime:runtime-saveable:1.10.6
androidx.compose.runtime:runtime:1.10.6
androidx.compose.ui:ui-android:1.10.6
androidx.compose.ui:ui-geometry-android:1.10.6
androidx.compose.ui:ui-geometry:1.10.6
androidx.compose.ui:ui-graphics-android:1.10.6
androidx.compose.ui:ui-graphics:1.10.6
androidx.compose.ui:ui-text-android:1.10.6
androidx.compose.ui:ui-text:1.10.6
androidx.compose.ui:ui-tooling-preview-android:1.10.6
androidx.compose.ui:ui-tooling-preview:1.10.6
androidx.compose.ui:ui-unit-android:1.10.6
androidx.compose.ui:ui-unit:1.10.6
androidx.compose.ui:ui-util-android:1.10.6
androidx.compose.ui:ui-util:1.10.6
androidx.compose.ui:ui:1.10.6
androidx.concurrent:concurrent-futures:1.1.0
androidx.core:core-ktx:1.18.0
androidx.core:core-viewtree:1.0.0
androidx.core:core:1.18.0
androidx.customview:customview-poolingcontainer:1.0.0
androidx.documentfile:documentfile:1.0.0
androidx.dynamicanimation:dynamicanimation:1.0.0
androidx.emoji2:emoji2:1.4.0
androidx.graphics:graphics-path:1.0.1
androidx.interpolator:interpolator:1.0.0
androidx.legacy:legacy-support-core-utils:1.0.0
androidx.lifecycle:lifecycle-common-java8:2.10.0
androidx.lifecycle:lifecycle-common-jvm:2.10.0
androidx.lifecycle:lifecycle-common:2.10.0
androidx.lifecycle:lifecycle-livedata-core-ktx:2.10.0
androidx.lifecycle:lifecycle-livedata-core:2.10.0
androidx.lifecycle:lifecycle-livedata:2.10.0
androidx.lifecycle:lifecycle-process:2.10.0
androidx.lifecycle:lifecycle-runtime-android:2.10.0
androidx.lifecycle:lifecycle-runtime-compose-android:2.10.0
androidx.lifecycle:lifecycle-runtime-compose:2.10.0
androidx.lifecycle:lifecycle-runtime-ktx-android:2.10.0
androidx.lifecycle:lifecycle-runtime-ktx:2.10.0
androidx.lifecycle:lifecycle-runtime:2.10.0
androidx.lifecycle:lifecycle-viewmodel-android:2.10.0
androidx.lifecycle:lifecycle-viewmodel-compose-android:2.10.0
androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0
androidx.lifecycle:lifecycle-viewmodel-ktx:2.10.0
androidx.lifecycle:lifecycle-viewmodel-savedstate-android:2.10.0
androidx.lifecycle:lifecycle-viewmodel-savedstate:2.10.0
androidx.lifecycle:lifecycle-viewmodel:2.10.0
androidx.loader:loader:1.0.0
androidx.localbroadcastmanager:localbroadcastmanager:1.0.0
androidx.navigationevent:navigationevent-android:1.0.0
androidx.navigationevent:navigationevent-compose-android:1.0.0
androidx.navigationevent:navigationevent-compose:1.0.0
androidx.navigationevent:navigationevent:1.0.0
androidx.print:print:1.0.0
androidx.profileinstaller:profileinstaller:1.4.0
androidx.savedstate:savedstate-android:1.4.0
androidx.savedstate:savedstate-compose-android:1.4.0
androidx.savedstate:savedstate-compose:1.4.0
androidx.savedstate:savedstate-ktx:1.4.0
androidx.savedstate:savedstate:1.4.0
androidx.startup:startup-runtime:1.1.1
androidx.tracing:tracing:1.2.0
androidx.transition:transition:1.6.0
androidx.versionedparcelable:versionedparcelable:1.1.1
androidx.window:window-core-android:1.5.0
androidx.window:window-core:1.5.0
androidx.window:window:1.5.0
com.google.guava:listenablefuture:1.0
commons-codec:commons-codec:1.19.0
commons-io:commons-io:2.20.0
org.apache.commons:commons-compress:1.28.0
org.apache.commons:commons-lang3:3.18.0
org.jetbrains:annotations:23.0.0
org.jetbrains.kotlin:kotlin-stdlib:2.3.20
org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0
org.jetbrains.kotlinx:kotlinx-coroutines-bom:1.9.0
org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.9.0
org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0
org.jetbrains.kotlinx:kotlinx-serialization-bom:1.7.3
org.jetbrains.kotlinx:kotlinx-serialization-core-jvm:1.7.3
org.jetbrains.kotlinx:kotlinx-serialization-core:1.7.3
org.jspecify:jspecify:1.0.0
```
