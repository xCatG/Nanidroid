# Satori Source-Encoding Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Remove NDK invalid-source-encoding warnings from legacy Satori/shared C++ without changing CP932 bytes used by narrow runtime literals.

**Architecture:** A deterministic Python tool converts the discovered `jni/_` and `jni/satori` C/C++ closure from CP932 to UTF-8. It emits non-ASCII narrow literal bytes as adjacent ASCII `\\xNN` chunks, leaving comments/identifiers as UTF-8, and supports `--check` to prevent drift.

**Tech Stack:** Python standard library, C++14, Android NDK 28.0.13004108, Gradle.

## Global Constraints

- Scope only the 57 files under `jni/_` and `jni/satori`; do not convert YAYA or Kawari.
- Preserve each file's line endings.
- Preserve every narrow-literal CP932 byte; split escape chunks before ASCII hex digits to prevent `\\x` run-on.
- Do not suppress `-Winvalid-source-encoding`.
- Verify arm64-v8a and x86_64, then `testDebugUnitTest assembleDebug`.

---

### Task 1: Build the deterministic converter/verifier

**Files:**
- Create: `tools/normalize_satori_source_encoding.py`
- Create: `tools/test_normalize_satori_source_encoding.py`

**Interfaces:** `python tools/normalize_satori_source_encoding.py --check` exits nonzero for a non-UTF-8 scoped source or literal-byte manifest mismatch; `--write` is the only source-rewrite operation.

- [ ] **Step 1: Write a failing synthetic test**

```python
def test_preserves_cp932_literal_bytes_and_prevents_hex_run_on(tmp_path):
    source = tmp_path / "sample.cpp"
    source.write_bytes(b'const char* s = "\\x82\\xA0A";\\r\\n')
    assert run("--check", source) != 0
    assert run("--write", source) == 0
    assert source.read_bytes() == b'const char* s = "\\\\x82\\\\xA0" "A";\\r\\n'
```

- [ ] **Step 2: Run it red**

Run: `python tools/test_normalize_satori_source_encoding.py`

Expected: fail because the converter does not exist.

- [ ] **Step 3: Implement the lexical converter**

Use a byte-oriented lexer for comments, escaped character/string literals, raw strings, and preprocessor lines. Decode CP932 outside literals to UTF-8 and emit original non-ASCII literal bytes as `\\xNN` chunks.

- [ ] **Step 4: Run tests green**

Run: `python tools/test_normalize_satori_source_encoding.py`

- [ ] **Step 5: Commit**

Run: `git add tools/normalize_satori_source_encoding.py tools/test_normalize_satori_source_encoding.py && git commit -m "tool: verify Satori source encoding"`

### Task 2: Convert the compiled closure

**Files:**
- Modify: scoped `jni/_/*.{cpp,h}` and `jni/satori/*.{cpp,h}` files
- Modify: `tools/normalize_satori_source_encoding.py` literal manifest

- [ ] **Step 1: Capture a failing invariant**

Run: `python tools/normalize_satori_source_encoding.py --check`

Expected: nonzero with the CP932 source-file list.

- [ ] **Step 2: Perform the single mechanical rewrite**

Run: `python tools/normalize_satori_source_encoding.py --write && python tools/normalize_satori_source_encoding.py --check`

- [ ] **Step 3: Inspect semantic diff**

Run: `git diff --check && git diff --word-diff=porcelain -- jni/_ jni/satori`

Expected: no line-ending churn and only encoding/literal spelling changes.

- [ ] **Step 4: Commit**

Run: `git add jni/_ jni/satori tools/normalize_satori_source_encoding.py && git commit -m "fix: normalize Satori native source encoding"`

### Task 3: Verify and review

**Files:**
- Test: `tools/test_normalize_satori_source_encoding.py`

- [ ] **Step 1: Verify source invariant**

Run: `python tools/test_normalize_satori_source_encoding.py && python tools/normalize_satori_source_encoding.py --check`

- [ ] **Step 2: Force the native build**

Run: `./gradlew.bat externalNativeBuildDebug --rerun-tasks --warning-mode all`

Expected: no `-Winvalid-source-encoding` diagnostic.

- [ ] **Step 3: Verify application build**

Run: `./gradlew.bat testDebugUnitTest assembleDebug --no-daemon --warning-mode all`

- [ ] **Step 4: Open PR**

Open a PR with `Fixes #335`, the byte-preservation invariant, these commands, and `@codex review`.
