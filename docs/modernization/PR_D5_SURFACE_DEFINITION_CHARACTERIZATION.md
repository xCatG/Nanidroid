# PR D5 — Surface-definition structural characterization

## Scope

This slice characterizes the existing production boundary:

```text
synthetic surfaces.txt bytes in an empty temporary shell directory
  -> SurfaceReader(SurfaceManager, shell root, descriptor path)
  -> sorted SurfaceManager / ShellSurface structural snapshot
```

The same-package JVM test reads the real package-private surface, collision,
animation, and frame model. Sorting is test-only normalization for map keys;
frame list order remains production order. The test does not copy parser logic
or add a production accessor. It passes a shell root with the platform file
separator and compares constructed paths through `File.getAbsolutePath()`.

The temporary shell contains only `surfaces.txt`. There are no PNG files, so
the exercised path never asks `BitmapFactory`, `Resources`, or a drawable to
decode or compose an image. Android default-return stubs cover incidental
timing and logging calls only; assertions cover ordinary Java model state.

D4 remains deferred separately. D5 makes no claim that the deferred native
engine slice is complete.

No production Java, C++, resource, manifest, JNI, native toolchain, or APK
contract behavior is changed by this slice.

## Fixture manifest and provenance

The single 254-byte fixture is original synthetic text embedded in
`SurfaceDefinitionCharacterizationTest`. It is encoded with Java's
`Shift_JIS` charset because that is what `SurfaceReader` requests, but every
fixture character is ASCII-compatible. The resulting bytes therefore do not
distinguish Shift-JIS from other ASCII-compatible decoders and this slice makes
no surface-definition encoding claim. The test recalculates SHA-256 before
production code sees the bytes.

| Fixture | Line endings | SHA-256 | Structural result |
| --- | --- | --- | --- |
| Grouped `surface0,surface10` using `Ninterval`/`Npattern`, plus `surface2` using `animationN.interval`/`animationN.pattern` | LF | `86714964606059af816e2915317d411bc55a5066318542714ef31274382b4b6f` | Numeric ids `0,2,10`; distinct grouped models; direct and zero-padded fallback paths; collision `Head` at `(1,2)` with size `10x20`; talk animation `0`; reset frames at waits `50,75`; equivalent structural snapshots across both grammars |

The old-syntax reset lines deliberately contain non-zero coordinates. Current
production retains the frame order and waits but stores both reset-frame
offsets as `(0,0)`. That coordinate loss is recorded as legacy-observed, not as
a migration requirement.

## Observable classifications

Required during a mechanical parser replacement:

- a grouped declaration produces separate `ShellSurface` instances for every
  declared id;
- ids are compared in numeric sorted order rather than `HashMap` order;
- direct and zero-padded fallback filenames are constructed beneath the shell
  root using the platform path separator;
- collision id, name, start point, width, and height are preserved;
- the conventional old and new animation grammars normalize to the same talk
  interval-to-animation mapping, ordered reset-frame types, and waits;
- exact manager lookup succeeds, generic missing lookup returns null, and
  missing Sakura/Kero ids fall back to loaded surfaces `0`/`10`.

Legacy-observed only:

- reset-frame coordinates supplied by the old grammar are discarded and remain
  zero in the loaded frame model.

The snapshot intentionally does not use `dumpSurfaces()`: its nested
`Hashtable` order is not a semantic contract. It also does not call animation
selection APIs that use `Math.random()`.

## TDD evidence

### Red

1. Adding `SurfaceDefinitionCharacterizationTest` to the D1-D3 base caused
   `verifyCharacterizationTestIsolation` to fail before execution and name the
   new file as the sole unexpected JVM source.
2. After temporarily admitting the exact source, the semantic snapshot
   intentionally expected the first reset wait to be `51`. Three D5 tests ran;
   the required snapshot failed with the unchanged production value `50`, while
   every other collision, animation, frame, and offset field matched.
3. Temporary missing-expected, ordinary unexpected `test/jvm`, and unexpected
   `src/testRelease` paths were each rejected by the Gradle guard and the
   independent Python exact-source oracle. The failures named the relevant
   missing or unexpected path.

The fresh worktree initially lacked generated native artifacts. The first two
non-root legacy attempts compiled but failed at Ant signing because Android's
home was not writable or did not exist. Running UID 1001 with `HOME=/tmp` and
`ANDROID_SDK_HOME=/tmp` supplied the required writable signing home and
completed the frozen legacy/CMake parity build. This was a harness prerequisite,
not a D5 behavior failure.

### Green

The wait expectation was restored to the observed value `50`. All three D5
tests pass through unchanged `SurfaceReader`, `SurfaceManager`, and
`ShellSurface`; the existing D1 eight, D2 seven, and D3 eight tests remain in
the same task.

### Refactor

Fixture writing/hashing, numeric id sorting, platform-normalized path
construction, and semantic snapshots share small test helpers. The snapshot
sorts collision, interval-type, and animation keys and retains frame list
order. The test also asserts that `surfaces.txt` is the temporary shell's sole
file. Absolute temporary paths are asserted separately from the portable
semantic snapshot.

## Harness isolation

The executable exact-source allowlist contains only:

- `DescReaderCharacterizationTest.java`
- `SakuraScriptCharacterizationTest.java`
- `ShioriEnvelopeCharacterizationTest.java`
- `SurfaceDefinitionCharacterizationTest.java`

Any missing expected source, or any fifth Java or Kotlin source under a
conventional app JVM unit-test tree matching `src/test*` or under `test/jvm/`,
fails `verifyCharacterizationTestIsolation`. Production and `androidTest`
trees remain excluded. The Python source-set oracle independently requires the
same exact four files. Every generated `test*UnitTest` task depends on the
Gradle guard.

## Non-goals and limits

This slice does not:

- fix or translate `SurfaceReader`, `SurfaceManager`, or `ShellSurface`;
- close the parser's reader or redesign its filesystem seam;
- scan, decode, validate, or hash PNG files;
- characterize transparency, dimensions, elements, overlays, drawable
  composition, animation execution, timing, or random animation choice;
- use a real ghost corpus or settle supported surface formats and encodings;
- characterize path traversal, symlink, storage, or archive-extraction policy;
- change Kotlin, dependencies, SDK/target/ABI policy, native code, Compose,
  lifecycle, or UI behavior.

Rendering and resource behavior needs a separately justified Android device,
emulator, or resource-aware harness. It must not be inferred from default-return
JVM stubs.

## Verification

Focused characterization:

```text
./gradlew testDebugUnitTest \
  --tests com.cattailsw.nanidroid.SurfaceDefinitionCharacterizationTest
```

The standard container pipeline runs the 26 D1/D2/D3/D5 tests, requires fresh
JUnit XML, assembles the APK, and executes the frozen APK/native parity gates:

```text
docker compose -f .devcontainer/compose.yaml run --rm dev \
  ./docker/gradle/build.sh
```
