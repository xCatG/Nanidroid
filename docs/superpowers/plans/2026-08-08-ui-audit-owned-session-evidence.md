# UI Audit Owned-Session Evidence Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the UI-audit verifier accept manual interaction PNGs only when their hashes were checkpointed during the audit-owned emulator session.

**Architecture:** Add small PowerShell helpers that build and validate an `interactionCapture` summary record. The capture workflow pauses for an explicit operator checkpoint before cleanup; manual completion reuses the validator before accepting the interaction-evidence table.

**Tech Stack:** PowerShell 7, JSON report artifacts, existing `-DryRun` host-only contract probes.

## Global Constraints

- Modify only `scripts/run-ui-visual-audit.ps1` plus this issue's documentation.
- Preserve existing failure handling, cleanup ordering, AVD ownership, and no-automatic-cancellation policy.
- Do not add dependencies, alter #255 worktree provenance, #256 SHIORI payload evidence, or #251 process-tree ownership.
- Keep the change fail-closed for missing, substituted, stale, duplicate, or mismatched session evidence.

---

### Task 1: Record manual interaction evidence in the live owned session

**Files:**

- Modify: `scripts/run-ui-visual-audit.ps1:907-913, 1503-1577`
- Test: `scripts/run-ui-visual-audit.ps1:Invoke-DryRunSelfTest`

**Interfaces:**

- Consumes: `Manifest.interactionEvidence`, `$script:captureProvenance`, `$script:ownedEmulator`, `$script:ownedEmulatorStartTimeUtcTicks`, `$DeviceSerial`, `$AvdName`, and `$SnapshotName`.
- Produces: `summary.interactionCapture` with session identity, capture provenance, and ordered `{ artifactPath, sha256 }` artifact records.

- [ ] **Step 1: Write the failing dry-run contract probe**

```powershell
$validInteractionCapture = New-InteractionCaptureRecord $Manifest
Assert-InteractionCaptureRecord $Manifest $validInteractionCapture $validInteractionCapture.session $capturedProvenance
foreach ($mutation in @('missing-artifact', 'substituted-path', 'duplicate-artifact', 'changed-hash', 'changed-session', 'changed-apk')) {
    $mutated = $validInteractionCapture | ConvertTo-Json -Depth 16 | ConvertFrom-Json
    switch ($mutation) {
        'missing-artifact' { $mutated.artifacts = @($mutated.artifacts | Select-Object -First 1) }
        'substituted-path' { $mutated.artifacts[0].artifactPath = 'interaction\\substituted.png' }
        'duplicate-artifact' { $mutated.artifacts[1].artifactPath = $mutated.artifacts[0].artifactPath }
        'changed-hash' { $mutated.artifacts[0].sha256 = ('b' * 64) }
        'changed-session' { $mutated.session.deviceSerial = 'emulator-9999' }
        'changed-apk' { $mutated.captureProvenance.debugApkSha256 = ('b' * 64) }
    }
    $failed = $false
    try { Assert-InteractionCaptureRecord $Manifest $mutated $validInteractionCapture.session $capturedProvenance } catch { $failed = $true }
    if (-not $failed) { Fail "Interaction session-record $mutation probe unexpectedly passed." 'dry-run' }
}
```

- [ ] **Step 2: Run the dry-run probe to verify it fails**

Run: `pwsh -NoProfile -ExecutionPolicy Bypass -File scripts/run-ui-visual-audit.ps1 -DryRun -CorpusRoots C:\work\src\Nanidroid\2elf-2.46.nar,C:\work\src\Nanidroid\build\ui-audit\ghosts,C:\work\src\Nanidroid\build\ui-audit\pcPets`

Expected: the new probe fails because `New-InteractionCaptureRecord` and `Assert-InteractionCaptureRecord` do not exist.

- [ ] **Step 3: Add minimal record and validation helpers**

```powershell
function New-InteractionCaptureRecord([object]$Manifest) {
    # Require each declared PNG, validate it, and return ordered session/provenance/artifact data.
}

function Assert-InteractionCaptureRecord([object]$Manifest, [object]$Record, [object]$Session, [object]$Provenance) {
    # Require exact session, provenance, path, count, and SHA-256 equality.
}
```

- [ ] **Step 4: Add the explicit checkpoint before cleanup**

```powershell
Write-Host 'Capture the two required interaction PNGs from this owned emulator session, then press Enter.'
[Console]::ReadLine() | Out-Null
$script:interactionCapture = New-InteractionCaptureRecord $uiManifest
```

Store the record in `Write-ReportSummary` before the existing `finally` cleanup uninstalls the packages or stops the emulator.

- [ ] **Step 5: Run the dry-run probe to verify it passes**

Run: `pwsh -NoProfile -ExecutionPolicy Bypass -File scripts/run-ui-visual-audit.ps1 -DryRun -CorpusRoots C:\work\src\Nanidroid\2elf-2.46.nar,C:\work\src\Nanidroid\build\ui-audit\ghosts,C:\work\src\Nanidroid\build\ui-audit\pcPets`

Expected: exit code 0 and `Dry-run passed` after valid and all mutated session-evidence probes pass.

### Task 2: Require checkpointed session evidence at manual completion

**Files:**

- Modify: `scripts/run-ui-visual-audit.ps1:1095-1189`
- Test: `scripts/run-ui-visual-audit.ps1:Invoke-DryRunSelfTest`

**Interfaces:**

- Consumes: `summary.interactionCapture`, `summary.captureProvenance`, `summary.deviceSerial`, `summary.avdName`, `summary.snapshotName`, the current interaction PNGs, and parsed interaction-evidence rows.
- Produces: a fail-closed manual verification result with the existing completed summary format.

- [ ] **Step 1: Extend the failing probe with an absent checkpoint record**

```powershell
$failed = $false
try { Assert-InteractionCaptureRecord $Manifest $null $validInteractionCapture.session $capturedProvenance } catch { $failed = $true }
if (-not $failed) { Fail 'Missing interaction session-record probe unexpectedly passed.' 'dry-run' }
```

- [ ] **Step 2: Run the dry-run probe to verify it fails**

Run: `pwsh -NoProfile -ExecutionPolicy Bypass -File scripts/run-ui-visual-audit.ps1 -DryRun -CorpusRoots C:\work\src\Nanidroid\2elf-2.46.nar,C:\work\src\Nanidroid\build\ui-audit\ghosts,C:\work\src\Nanidroid\build\ui-audit\pcPets`

Expected: the absent-checkpoint probe fails until manual completion calls the shared validator.

- [ ] **Step 3: Call the shared validator before PNG acceptance**

```powershell
$session = [pscustomobject]@{
    deviceSerial = $summary.deviceSerial
    avdName = $summary.avdName
    snapshotName = $summary.snapshotName
}
Assert-InteractionCaptureRecord $capturedManifest $summary.interactionCapture $session $summary.captureProvenance
```

Require every parsed interaction-evidence hash to equal its checkpointed hash before `Assert-CurrentReportPngEvidence` accepts the report.

- [ ] **Step 4: Run the dry-run probe to verify it passes**

Run: `pwsh -NoProfile -ExecutionPolicy Bypass -File scripts/run-ui-visual-audit.ps1 -DryRun -CorpusRoots C:\work\src\Nanidroid\2elf-2.46.nar,C:\work\src\Nanidroid\build\ui-audit\ghosts,C:\work\src\Nanidroid\build\ui-audit\pcPets`

Expected: exit code 0 and all session, provenance, path, duplication, and hash mutations are rejected.

- [ ] **Step 5: Review local simplification and commit**

Inspect only the modified helpers and callers. Keep shared session construction explicit; do not reformat unrelated portions of the audit script.

```powershell
git diff --check
git add scripts/run-ui-visual-audit.ps1 docs/superpowers/specs/2026-08-08-ui-audit-owned-session-evidence-design.md docs/superpowers/plans/2026-08-08-ui-audit-owned-session-evidence.md
git commit -m "test: bind UI audit interaction evidence to session"
```

## Self-Review

- Scope covers the required live checkpoint and completion-time fail-closed validation.
- Every mutated record condition has an explicit dry-run probe.
- The record helpers use the same field names in both capture and verification.
- No step changes unrelated issue contracts or contains placeholders.
