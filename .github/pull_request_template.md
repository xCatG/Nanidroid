## Contract

Describe the single behavior, policy, or migration boundary this PR owns.

## Testing mode

- [ ] Policy/acceptance gate
- [ ] Characterization-preserving refactor
- [ ] Red-green-refactor

### Evidence before the change

For a behavior change, link the failing specification test. For a migration,
record the passing baseline or differential fixture. For infrastructure, state
the acceptance condition.

### Evidence after the change

List exact commands and results. Separate JVM, native, emulator/device, and
manual verification.

## Behavior ledger

- **Preserved:**
- **Intentionally changed:**
- **Unsupported or removed:**
- **Insecure behavior not preserved:**

## Non-goals

List adjacent work deliberately excluded from this PR.

## Rollback and data impact

State whether reverting code is sufficient. Describe any persistent data,
storage, preferences, native state, or signing implications.

## Checklist

- [ ] The branch targets `feature/modernization`.
- [ ] The change has one reviewable behavioral contract.
- [ ] File moves are separate from functional edits.
- [ ] Required fixtures are synthetic or have documented provenance.
- [ ] No SDK, NDK, Gradle, or dependency version floats on `latest`.
- [ ] `python tools/check_repository_hygiene.py` passes.
- [ ] `feature/modernization` remains buildable after merge.
