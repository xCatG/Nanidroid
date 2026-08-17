from __future__ import annotations

import copy
import contextlib
import importlib.util
import io
import unittest
from pathlib import Path
from unittest import mock


ROOT = Path(__file__).resolve().parents[1]
TOOL = ROOT / "tools" / "verify_phase1_shipped_state_audit.py"
SPEC = importlib.util.spec_from_file_location("phase1_audit", TOOL)
assert SPEC is not None and SPEC.loader is not None
phase1_audit = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(phase1_audit)


class Phase1ShippedStateAuditTest(unittest.TestCase):
    def ledger(self) -> dict[str, object]:
        return copy.deepcopy(phase1_audit.load_ledger(phase1_audit.LEDGER))

    def assert_failure(self, data: object, text: str) -> None:
        failures = phase1_audit.validate_ledger(data, ROOT)
        self.assertTrue(
            any(text in failure for failure in failures),
            f"expected {text!r} in {failures!r}",
        )

    def resource(self, data: dict[str, object], resource_id: str) -> dict[str, object]:
        return next(
            resource
            for resource in data["persistentResources"]
            if resource["id"] == resource_id
        )

    def evidence(self, data: dict[str, object], evidence_id: str) -> dict[str, object]:
        return next(item for item in data["evidence"] if item["id"] == evidence_id)

    def test_committed_path_a_ledger_is_valid(self) -> None:
        self.assertEqual([], phase1_audit.validate_ledger(self.ledger(), ROOT))

    def test_rejects_unknown_schema_version(self) -> None:
        data = self.ledger()
        data["schemaVersion"] = 99
        self.assert_failure(data, "schemaVersion must be 1")

    def test_rejects_boolean_or_float_schema_version(self) -> None:
        for value in (True, 1.0):
            with self.subTest(value=value):
                data = self.ledger()
                data["schemaVersion"] = value
                self.assert_failure(
                    data,
                    "schemaVersion must be 1 (a true JSON integer)",
                )

    def test_rejects_non_exact_audit_date(self) -> None:
        mutations = (
            lambda data: data.__setitem__("auditDate", "2026-08-16"),
            lambda data: data.pop("auditDate"),
            lambda data: data.__setitem__("auditDate", 20260817),
        )
        for mutate in mutations:
            with self.subTest(mutation=mutate):
                data = self.ledger()
                mutate(data)
                self.assert_failure(
                    data,
                    "auditDate must exactly match 2026-08-17",
                )

    def test_rejects_boolean_or_float_repository_version_code(self) -> None:
        for value in (True, 6.0):
            with self.subTest(value=value):
                data = self.ledger()
                data["repository"]["versionCode"] = value
                self.assert_failure(
                    data,
                    "repository.versionCode must be 6 (a true JSON integer)",
                )

    def test_schema_v1_rejects_unknown_object_keys(self) -> None:
        mutations = (
            (
                "ledger",
                lambda data: data.__setitem__("compatibilityMode", "contradiction"),
                "ledger keys must exactly match schema version 1",
            ),
            (
                "repository",
                lambda data: data["repository"].__setitem__(
                    "releasePublished",
                    True,
                ),
                "repository keys must exactly match schema version 1",
            ),
            (
                "writer epoch",
                lambda data: data["writerEpochs"][0].__setitem__(
                    "writerDisabled",
                    True,
                ),
                "writer epoch keys must exactly match schema version 1",
            ),
            (
                "decision",
                lambda data: data["decision"].__setitem__(
                    "fallbackPath",
                    "B",
                ),
                "decision keys must exactly match schema version 1",
            ),
            (
                "distribution",
                lambda data: data["distribution"].__setitem__(
                    "privatelyDistributed",
                    True,
                ),
                "distribution keys must exactly match schema version 1",
            ),
            (
                "distribution.github",
                lambda data: data["distribution"]["github"].__setitem__(
                    "deletedReleaseCount",
                    1,
                ),
                "distribution.github keys must exactly match schema version 1",
            ),
            (
                "distribution channel",
                lambda data: data["distribution"]["channels"][0].__setitem__(
                    "stateCapable",
                    True,
                ),
                "distribution channel keys must exactly match schema version 1",
            ),
        )
        for category, mutate, expected_failure in mutations:
            with self.subTest(category=category):
                data = self.ledger()
                mutate(data)
                self.assert_failure(data, expected_failure)

    def test_rejects_unresolved_decision_path(self) -> None:
        data = self.ledger()
        data["decision"]["path"] = "UNRESOLVED"
        self.assert_failure(data, "schemaVersion 1 requires decision.path exactly A")

    def test_main_rejects_unresolved_decision_path(self) -> None:
        data = self.ledger()
        data["decision"]["path"] = "UNRESOLVED"
        stderr = io.StringIO()
        with mock.patch.object(phase1_audit, "load_ledger", return_value=data):
            with contextlib.redirect_stderr(stderr):
                result = phase1_audit.main()
        self.assertEqual(1, result)
        self.assertIn(
            "schemaVersion 1 requires decision.path exactly A",
            stderr.getvalue(),
        )

    def test_valid_json_top_level_non_object_is_a_structured_failure(self) -> None:
        self.assertEqual(
            ["ledger must be a top-level object"],
            phase1_audit.validate_ledger([], ROOT),
        )

        stderr = io.StringIO()
        with mock.patch.object(phase1_audit, "load_ledger", return_value=[]):
            with contextlib.redirect_stderr(stderr):
                result = phase1_audit.main()
        self.assertEqual(1, result)
        self.assertIn("ledger must be a top-level object", stderr.getvalue())

    def test_rejects_path_a_without_confirmed_owner_attestation(self) -> None:
        data = self.ledger()
        data["distribution"]["ownerAttestation"]["confirmed"] = False
        self.assert_failure(
            data,
            "Path A ownerAttestation.confirmed must be exactly true",
        )

    def test_rejects_path_a_when_state_capable_apk_was_distributed(self) -> None:
        data = self.ledger()
        data["distribution"]["ownerAttestation"][
            "stateCapableApkDistributed"
        ] = True
        self.assert_failure(
            data,
            "Path A ownerAttestation.stateCapableApkDistributed must be exactly false",
        )

    def test_rejects_numeric_aliases_for_path_a_attestation_booleans(self) -> None:
        mutations = (
            (
                "confirmed",
                1,
                "Path A ownerAttestation.confirmed must be exactly true",
            ),
            (
                "confirmed",
                1.0,
                "Path A ownerAttestation.confirmed must be exactly true",
            ),
            (
                "stateCapableApkDistributed",
                0,
                "Path A ownerAttestation.stateCapableApkDistributed must be exactly false",
            ),
            (
                "stateCapableApkDistributed",
                0.0,
                "Path A ownerAttestation.stateCapableApkDistributed must be exactly false",
            ),
            (
                "signingKeyRecovered",
                0,
                "Path A ownerAttestation.signingKeyRecovered must be exactly false",
            ),
            (
                "signingKeyRecovered",
                0.0,
                "Path A ownerAttestation.signingKeyRecovered must be exactly false",
            ),
        )
        for field, value, expected_failure in mutations:
            with self.subTest(field=field, value=value):
                data = self.ledger()
                data["distribution"]["ownerAttestation"][field] = value
                self.assert_failure(data, expected_failure)

    def test_rejects_every_mutated_path_a_attestation_field(self) -> None:
        mutations = {
            "confirmed": False,
            "date": "2026-08-16",
            "stateCapableApkDistributed": True,
            "signingKeyRecovered": True,
            "statement": "No state-capable APK was distributed.",
        }
        for field, value in mutations.items():
            with self.subTest(field=field):
                data = self.ledger()
                data["distribution"]["ownerAttestation"][field] = value
                self.assert_failure(data, "Path A owner attestation must exactly match")

    def test_rejects_path_a_attestation_with_missing_or_extra_field(self) -> None:
        data = self.ledger()
        del data["distribution"]["ownerAttestation"]["signingKeyRecovered"]
        self.assert_failure(data, "Path A owner attestation must exactly match")

        data = self.ledger()
        data["distribution"]["ownerAttestation"]["note"] = "extra"
        self.assert_failure(data, "Path A owner attestation must exactly match")

    def test_rejects_mutated_path_a_attestation_evidence(self) -> None:
        mutations = {
            "type": "repository-document",
            "claim": "No APK was distributed.",
            "source": "Unspecified",
            "observedAt": "2026-08-16",
        }
        for field, value in mutations.items():
            with self.subTest(field=field):
                data = self.ledger()
                self.evidence(data, "owner-attestation-2026-08-17")[field] = value
                self.assert_failure(
                    data,
                    "Path A evidence contract mismatch: owner-attestation-2026-08-17",
                )

    def test_rejects_unknown_distribution_channel_for_path_a(self) -> None:
        data = self.ledger()
        data["distribution"]["channels"][0]["status"] = "unknown"
        self.assert_failure(data, "Path A requires every distribution channel")

    def test_rejects_missing_distribution_channel_id(self) -> None:
        data = self.ledger()
        data["distribution"]["channels"].pop()
        self.assert_failure(data, "distribution channel IDs must exactly match")

    def test_rejects_extra_distribution_channel_id(self) -> None:
        data = self.ledger()
        data["distribution"]["channels"].append({"id": "unexpected", "status": "none"})
        self.assert_failure(data, "distribution channel IDs must exactly match")

    def test_rejects_duplicate_distribution_channel_id(self) -> None:
        data = self.ledger()
        data["distribution"]["channels"].append(
            {"id": "github-releases", "status": "none"}
        )
        self.assert_failure(data, "distribution channel IDs must exactly match")

    def test_malformed_distribution_channel_entries_return_failures(self) -> None:
        for value in (None, "github-releases", [], {"id": []}):
            with self.subTest(value=value):
                data = self.ledger()
                data["distribution"]["channels"][0] = value
                try:
                    failures = phase1_audit.validate_ledger(data, ROOT)
                except (TypeError, AttributeError, KeyError):
                    self.fail(f"validate_ledger raised for malformed channel: {value!r}")
                self.assertTrue(
                    any("distribution channel entries must be objects" in failure for failure in failures),
                    failures,
                )

    def test_rejects_former_positive_path_b_fixture(self) -> None:
        data = self.ledger()
        data["decision"]["path"] = "B"
        data["decision"]["sequentialUpgradeEnforced"] = True
        data["distribution"]["channels"][-1]["status"] = "state-capable"
        data["distribution"]["ownerAttestation"]["stateCapableApkDistributed"] = True
        data["distribution"]["ownerAttestation"][
            "statement"
        ] = "A state-capable APK was distributed."
        self.evidence(data, "owner-attestation-2026-08-17")[
            "claim"
        ] = "A state-capable APK was distributed."
        self.assert_failure(data, "schemaVersion 1 requires decision.path exactly A")

    def test_rejects_former_positive_path_c_fixture(self) -> None:
        data = self.ledger()
        data["decision"]["path"] = "C"
        data["decision"]["compatibilityRemovalFloor"] = "version 2"
        data["distribution"]["channels"][-1]["status"] = "state-capable"
        data["distribution"]["ownerAttestation"]["stateCapableApkDistributed"] = True
        data["distribution"]["ownerAttestation"][
            "statement"
        ] = "A state-capable APK was distributed."
        self.evidence(data, "owner-attestation-2026-08-17")[
            "claim"
        ] = "A state-capable APK was distributed."
        self.assert_failure(data, "schemaVersion 1 requires decision.path exactly A")

    def test_rejects_nonexistent_writer_commit(self) -> None:
        data = self.ledger()
        data["writerEpochs"][0]["commit"] = "0" * 40
        self.assert_failure(data, "writer commit does not exist")

    def test_rejects_writer_commit_outside_audited_head(self) -> None:
        data = self.ledger()
        data["repository"]["auditedHead"] = data["writerEpochs"][0]["commit"]
        data["writerEpochs"][-1]["commit"] = phase1_audit.git_text(
            ROOT, "rev-parse", "HEAD"
        )
        self.assert_failure(data, "writer commit is not an ancestor")

    def test_rejects_real_newer_descendant_as_audited_head(self) -> None:
        data = self.ledger()
        newer_descendant = phase1_audit.git_text(ROOT, "rev-parse", "HEAD")
        self.assertNotEqual(
            "f7d037bc066ff648d73b4c2d403a890765b44523",
            newer_descendant,
        )
        data["repository"]["auditedHead"] = newer_descendant
        self.assert_failure(
            data,
            "repository.auditedHead must exactly match "
            "f7d037bc066ff648d73b4c2d403a890765b44523",
        )

    def test_rejects_incorrect_app_identity(self) -> None:
        data = self.ledger()
        data["repository"]["applicationId"] = "example.invalid"
        self.assert_failure(data, "application identity mismatch")

    def test_rejects_missing_required_writer_epoch(self) -> None:
        data = self.ledger()
        data["writerEpochs"] = data["writerEpochs"][1:]
        self.assert_failure(data, "missing writer epoch: nar-queue-workmanager")

    def test_rejects_extra_writer_epoch(self) -> None:
        data = self.ledger()
        data["writerEpochs"].append(copy.deepcopy(data["writerEpochs"][0]))
        data["writerEpochs"][-1]["id"] = "unexpected"
        self.assert_failure(data, "writer epoch contracts must exactly match")

    def test_rejects_writer_epoch_commit_substitution(self) -> None:
        data = self.ledger()
        data["writerEpochs"][0]["commit"] = data["writerEpochs"][1]["commit"]
        self.assert_failure(data, "writer epoch contract mismatch: nar-queue-workmanager")

    def test_rejects_missing_extra_or_substituted_introduced_path(self) -> None:
        mutations = (
            lambda paths: paths.pop(),
            lambda paths: paths.append("src/main/kotlin/example/Unexpected.kt"),
            lambda paths: paths.__setitem__(0, "build.gradle.kts"),
        )
        for mutate in mutations:
            data = self.ledger()
            mutate(data["writerEpochs"][0]["introducedPaths"])
            self.assert_failure(data, "writer epoch contract mismatch: nar-queue-workmanager")

    def test_writer_epoch_order_does_not_change_id_bound_contracts(self) -> None:
        data = self.ledger()
        data["writerEpochs"].reverse()
        self.assertEqual([], phase1_audit.validate_ledger(data, ROOT))

    def test_rejects_introduced_path_not_added_against_only_parent(self) -> None:
        data = self.ledger()
        real_git_text = phase1_audit.git_text

        def fake_git_text(repo_root: Path, *args: str) -> str:
            if args[:2] == ("diff-tree", "--no-commit-id"):
                return "M"
            return real_git_text(repo_root, *args)

        with mock.patch.object(phase1_audit, "git_text", side_effect=fake_git_text):
            self.assert_failure(data, "introduced path must be an addition")

    def test_rejects_writer_commit_without_exactly_one_parent(self) -> None:
        data = self.ledger()
        real_git_text = phase1_audit.git_text

        def fake_git_text(repo_root: Path, *args: str) -> str:
            if args[:3] == ("rev-list", "--parents", "-n"):
                commit = args[-1]
                return f"{commit} {'1' * 40} {'2' * 40}"
            return real_git_text(repo_root, *args)

        with mock.patch.object(phase1_audit, "git_text", side_effect=fake_git_text):
            self.assert_failure(data, "writer commit must have exactly one parent")

    def test_rejects_missing_required_persistent_resource(self) -> None:
        data = self.ledger()
        data["persistentResources"] = [
            resource
            for resource in data["persistentResources"]
            if resource["id"] != "workmanager-worker-fqcns"
        ]
        self.assert_failure(data, "missing persistent resource: workmanager-worker-fqcns")

    def test_rejects_extra_or_duplicate_persistent_resource(self) -> None:
        data = self.ledger()
        extra = copy.deepcopy(data["persistentResources"][0])
        extra["id"] = "unexpected"
        data["persistentResources"].append(extra)
        self.assert_failure(data, "persistent resource contracts must exactly match")

        data = self.ledger()
        data["persistentResources"].append(copy.deepcopy(data["persistentResources"][0]))
        self.assert_failure(data, "persistent resource IDs must be strings and unique")

    def test_rejects_mutation_of_every_persistent_resource_contract(self) -> None:
        data = self.ledger()
        resource_ids = [resource["id"] for resource in data["persistentResources"]]
        for resource_id in resource_ids:
            with self.subTest(resource_id=resource_id):
                mutated = self.ledger()
                self.resource(mutated, resource_id)["cleanupPolicy"] += " mutated"
                self.assert_failure(mutated, f"persistent resource contract mismatch: {resource_id}")

    def test_rejects_missing_extra_or_mutated_persistent_resource_fields(self) -> None:
        resource_id = "nar-download-queue"
        for field in ("ownership", "locations", "formats", "cleanupPolicy"):
            with self.subTest(field=field):
                data = self.ledger()
                resource = self.resource(data, resource_id)
                if isinstance(resource[field], list):
                    resource[field].append("unexpected")
                else:
                    resource[field] += " mutated"
                self.assert_failure(data, f"persistent resource contract mismatch: {resource_id}")

        data = self.ledger()
        del self.resource(data, resource_id)["formats"]
        self.assert_failure(data, f"persistent resource contract mismatch: {resource_id}")

        data = self.ledger()
        self.resource(data, resource_id)["extra"] = True
        self.assert_failure(data, f"persistent resource contract mismatch: {resource_id}")

    def test_downloadmanager_rows_uses_android_download_directory(self) -> None:
        data = self.ledger()
        self.resource(data, "downloadmanager-rows")["locations"] = [
            "external-files/Download/nar-downloads/<itemId>.nar"
        ]
        self.assertEqual([], phase1_audit.validate_ledger(data, ROOT))

    def test_requires_installed_live_ghost_trees_resource(self) -> None:
        data = self.ledger()
        data["persistentResources"] = [
            resource
            for resource in data["persistentResources"]
            if resource["id"] != "installed-live-ghost-trees"
        ]
        self.assert_failure(data, "missing persistent resource: installed-live-ghost-trees")

    def test_binds_installed_live_ghost_trees_contract(self) -> None:
        data = self.ledger()
        desired = {
            "id": "installed-live-ghost-trees",
            "ownership": "APP_OWNED_LIVE_PRODUCT_STATE_RETAIN",
            "locations": ["external-files/ghost/<validated-targetId>"],
            "formats": ["published-live-ghost-tree-usability-not-cleanup-ownership"],
            "cleanupPolicy": "Preserve from generic workflow cleanup; mutate only through exact transactional publication or recovery, or explicit user removal; no cleanup in audit PR",
        }
        data["persistentResources"] = [
            resource
            for resource in data["persistentResources"]
            if resource["id"] != desired["id"]
        ]
        desired["cleanupPolicy"] += " mutated"
        data["persistentResources"].append(desired)
        self.assert_failure(
            data,
            "persistent resource contract mismatch: installed-live-ghost-trees",
        )

    def test_installed_live_ghost_format_denies_cleanup_ownership(self) -> None:
        data = self.ledger()
        self.resource(data, "installed-live-ghost-trees")["formats"] = [
            "published-live-ghost-tree-usability-not-cleanup-ownership"
        ]
        self.assertEqual([], phase1_audit.validate_ledger(data, ROOT))

    def test_rejects_installed_live_ghost_usability_as_cleanup_ownership(self) -> None:
        data = self.ledger()
        self.resource(data, "installed-live-ghost-trees")["formats"] = [
            "published-usable-ghost-tree"
        ]
        self.assert_failure(
            data,
            "persistent resource contract mismatch: installed-live-ghost-trees",
        )

    def test_requires_durable_attention_notifications_resource(self) -> None:
        data = self.ledger()
        data["persistentResources"] = [
            resource
            for resource in data["persistentResources"]
            if resource["id"] != "durable-attention-notifications"
        ]
        self.assert_failure(
            data,
            "missing persistent resource: durable-attention-notifications",
        )

    def test_binds_durable_attention_notification_channel_tag_and_id(self) -> None:
        desired = {
            "id": "durable-attention-notifications",
            "ownership": "PLATFORM_OWNED_NOTIFICATION_EXACT_IDENTITY",
            "locations": [
                "channel|id=nanidroid_operation_attention|app-declared-initial-importance=IMPORTANCE_DEFAULT|description=@string/durable_attention_channel_description",
                "active-notification|tag=durable:<operationId>::<attemptId>|id=43",
            ],
            "formats": ["channel-runtime-user-sound-vibration-and-importance-not-fixed"],
            "cleanupPolicy": "Preserve channel identity and user configuration; reconcile or cancel active notifications only by exact durable:<operationId>::<attemptId> tag and ID 43 until deliberate migration or removal; no cleanup in audit PR",
        }
        mutations = (
            lambda resource: resource["locations"].__setitem__(
                0,
                resource["locations"][0].replace(
                    "nanidroid_operation_attention",
                    "nanidroid_operation_attention_v2",
                ),
            ),
            lambda resource: resource["locations"].__setitem__(
                1,
                resource["locations"][1].replace("durable:", "operation:"),
            ),
            lambda resource: resource["locations"].__setitem__(
                1,
                resource["locations"][1].replace("id=43", "id=44"),
            ),
        )
        for mutate in mutations:
            with self.subTest(mutation=mutate):
                data = self.ledger()
                resource = copy.deepcopy(desired)
                mutate(resource)
                data["persistentResources"] = [
                    item
                    for item in data["persistentResources"]
                    if item["id"] != desired["id"]
                ]
                data["persistentResources"].append(resource)
                self.assert_failure(
                    data,
                    "persistent resource contract mismatch: durable-attention-notifications",
                )

    def test_requires_nanidroid_service_foreground_notification_resource(self) -> None:
        data = self.ledger()
        data["persistentResources"] = [
            resource
            for resource in data["persistentResources"]
            if resource["id"] != "nanidroid-service-foreground-notification"
        ]
        self.assert_failure(
            data,
            "missing persistent resource: nanidroid-service-foreground-notification",
        )

    def test_binds_nanidroid_service_notification_channel_importance_and_id(self) -> None:
        desired = {
            "id": "nanidroid-service-foreground-notification",
            "ownership": "PLATFORM_OWNED_NOTIFICATION_EXACT_IDENTITY",
            "locations": [
                "channel|id=nanidroid_downloads|app-declared-initial-importance=IMPORTANCE_LOW",
                "foreground-notification|id=41",
            ],
            "formats": ["startForeground-service-notification"],
            "cleanupPolicy": "Preserve channel identity and user configuration and foreground notification ID 41 until NanidroidService is deliberately stopped and removed; no cleanup in audit PR",
        }
        mutations = (
            lambda resource: resource["locations"].__setitem__(
                0,
                resource["locations"][0].replace("nanidroid_downloads", "downloads"),
            ),
            lambda resource: resource["locations"].__setitem__(
                0,
                resource["locations"][0].replace("IMPORTANCE_LOW", "IMPORTANCE_DEFAULT"),
            ),
            lambda resource: resource["locations"].__setitem__(
                1,
                resource["locations"][1].replace("id=41", "id=42"),
            ),
        )
        for mutate in mutations:
            with self.subTest(mutation=mutate):
                data = self.ledger()
                resource = copy.deepcopy(desired)
                mutate(resource)
                data["persistentResources"] = [
                    item
                    for item in data["persistentResources"]
                    if item["id"] != desired["id"]
                ]
                data["persistentResources"].append(resource)
                self.assert_failure(
                    data,
                    "persistent resource contract mismatch: nanidroid-service-foreground-notification",
                )

    def test_rejects_dangling_decision_evidence_reference(self) -> None:
        data = self.ledger()
        data["decision"]["rationaleEvidenceIds"].append("missing-evidence")
        self.assert_failure(data, "dangling evidence reference: missing-evidence")

    def test_rejects_duplicate_evidence_ids(self) -> None:
        data = self.ledger()
        data["evidence"].append(copy.deepcopy(data["evidence"][0]))
        self.assert_failure(data, "duplicate evidence id")

    def test_rejects_unknown_evidence_type(self) -> None:
        data = self.ledger()
        data["evidence"][0]["type"] = "guess"
        self.assert_failure(data, "unknown evidence type")

    def test_binds_each_previously_loose_path_a_evidence_object(self) -> None:
        evidence_ids = (
            "git-writer-epochs",
            "git-app-identity-reuse",
            "github-releases-tags-empty-2026-08-17",
            "github-actions-no-post-writer-apk-2026-08-17",
            "baseline-artifacts-unavailable",
        )
        mutations = (
            lambda item: item.__setitem__("type", "owner-attestation"),
            lambda item: item.__setitem__("claim", "Valid but incorrect claim."),
            lambda item: item.__setitem__("source", "Valid but incorrect source"),
            lambda item: item.__setitem__("observedAt", "2026-08-16"),
            lambda item: item.pop("claim"),
            lambda item: item.__setitem__("note", "unexpected"),
        )
        for evidence_id in evidence_ids:
            for mutate in mutations:
                with self.subTest(evidence_id=evidence_id, mutation=mutate):
                    data = self.ledger()
                    mutate(self.evidence(data, evidence_id))
                    self.assert_failure(
                        data,
                        f"Path A evidence contract mismatch: {evidence_id}",
                    )

    def test_path_a_evidence_order_does_not_change_id_bound_contracts(self) -> None:
        data = self.ledger()
        data["evidence"].reverse()
        self.assertEqual([], phase1_audit.validate_ledger(data, ROOT))

    def test_allows_unrelated_generic_valid_evidence(self) -> None:
        data = self.ledger()
        data["evidence"].append(
            {
                "id": "unrelated-valid-evidence",
                "type": "repository-document",
                "claim": "A valid unrelated observation.",
                "source": "Independent document",
                "observedAt": "2026-08-17",
                "extension": {"reviewed": True},
            }
        )
        self.assertEqual([], phase1_audit.validate_ledger(data, ROOT))

    def test_rejects_unrelated_evidence_that_is_not_generic_valid(self) -> None:
        data = self.ledger()
        data["evidence"].append(
            {
                "id": "unrelated-invalid-evidence",
                "type": "repository-document",
                "claim": "",
                "source": "Independent document",
                "observedAt": "2026-08-17",
            }
        )
        self.assert_failure(
            data,
            "evidence unrelated-invalid-evidence requires nonempty claim",
        )

    def test_rejects_path_a_with_post_writer_apk_artifact(self) -> None:
        data = self.ledger()
        data["distribution"]["github"]["postWriterApkArtifactCount"] = 1
        self.assert_failure(
            data,
            "Path A GitHub postWriterApkArtifactCount must exactly match 0",
        )

    def test_rejects_mutated_path_a_github_observation_counts(self) -> None:
        mutations = {
            "releaseCount": (1, 0),
            "tagCount": (1, 0),
            "actionsArtifactCount": (191, 192),
            "postWriterApkArtifactCount": (1, 0),
            "postWriterReportOnlyArtifactCount": (1, 2),
        }
        for field, (value, expected) in mutations.items():
            with self.subTest(field=field):
                data = self.ledger()
                data["distribution"]["github"][field] = value
                self.assert_failure(
                    data,
                    f"Path A GitHub {field} must exactly match {expected}",
                )

    def test_rejects_json_booleans_for_path_a_zero_counts(self) -> None:
        for field in ("releaseCount", "tagCount", "postWriterApkArtifactCount"):
            with self.subTest(field=field):
                data = self.ledger()
                data["distribution"]["github"][field] = False
                self.assert_failure(
                    data,
                    f"Path A GitHub {field} must exactly match 0",
                )

    def test_rejects_missing_path_a_github_observation_counts(self) -> None:
        expected_counts = {
            "releaseCount": 0,
            "tagCount": 0,
            "actionsArtifactCount": 192,
            "postWriterApkArtifactCount": 0,
            "postWriterReportOnlyArtifactCount": 2,
        }
        for field, expected in expected_counts.items():
            with self.subTest(field=field):
                data = self.ledger()
                del data["distribution"]["github"][field]
                self.assert_failure(
                    data,
                    f"Path A GitHub {field} must exactly match {expected}",
                )

    def test_rejects_float_path_a_github_observation_counts(self) -> None:
        float_counts = {
            "releaseCount": 0.0,
            "tagCount": 0.0,
            "actionsArtifactCount": 192.0,
            "postWriterApkArtifactCount": 0.0,
            "postWriterReportOnlyArtifactCount": 2.0,
        }
        for field, value in float_counts.items():
            with self.subTest(field=field):
                data = self.ledger()
                data["distribution"]["github"][field] = value
                self.assert_failure(
                    data,
                    f"Path A GitHub {field} must exactly match {int(value)}",
                )

    def test_rejects_path_a_without_attestation_evidence_reference(self) -> None:
        data = self.ledger()
        data["decision"]["rationaleEvidenceIds"].remove("owner-attestation-2026-08-17")
        self.assert_failure(data, "Path A decision must reference owner attestation")

    def test_rejects_mutated_path_a_decision_fields(self) -> None:
        mutations = {
            "supportedUpgradeFloor": "Any modernization build",
            "sequentialUpgradeEnforced": True,
            "compatibilityRemovalFloor": "version 2",
        }
        for field, value in mutations.items():
            with self.subTest(field=field):
                data = self.ledger()
                data["decision"][field] = value
                self.assert_failure(data, f"Path A {field} must exactly match")

    def test_rejects_non_exact_or_duplicate_path_a_rationale_evidence_ids(self) -> None:
        mutations = (
            lambda ids: ids.pop(),
            lambda ids: ids.append(ids[0]),
            lambda ids: ids.__setitem__(0, "owner-attestation-2026-08-17"),
        )
        for mutate in mutations:
            with self.subTest(mutation=mutate):
                data = self.ledger()
                mutate(data["decision"]["rationaleEvidenceIds"])
                self.assert_failure(
                    data,
                    "Path A rationale evidence IDs must exactly match the required unique set",
                )

    def test_binds_separate_ghost_staging_and_published_contracts(self) -> None:
        data = self.ledger()
        staging = self.resource(data, "ghost-update-unpublished-staging")
        staging["locations"].append("<published-transaction>/candidate")
        self.assert_failure(
            data,
            "persistent resource contract mismatch: ghost-update-unpublished-staging",
        )

        data = self.ledger()
        published = self.resource(data, "ghost-update-transaction")
        published["formats"].append("owner-marker=required-after-publish")
        self.assert_failure(
            data,
            "persistent resource contract mismatch: ghost-update-transaction",
        )

    def test_binds_full_durable_pending_intent_action_identities(self) -> None:
        data = self.ledger()
        pending_intents = self.resource(data, "durable-pending-intents")
        pending_intents["locations"][0] = pending_intents["locations"][0].replace(
            "com.cattailsw.nanidroid.action.DURABLE_KEEP_WAITING",
            "DURABLE_KEEP_WAITING",
        )
        self.assert_failure(
            data,
            "persistent resource contract mismatch: durable-pending-intents",
        )

    def test_binds_qualified_backup_inclusion_boundary(self) -> None:
        data = self.ledger()
        backup = self.resource(data, "backup-device-transfer-boundaries")
        backup["formats"] = ["all unlisted state remains included"]
        self.assert_failure(
            data,
            "persistent resource contract mismatch: backup-device-transfer-boundaries",
        )

    def test_rejects_evidence_with_missing_source(self) -> None:
        data = self.ledger()
        del data["evidence"][0]["source"]
        self.assert_failure(data, "requires nonempty source")

    def test_rejects_evidence_with_empty_observed_at(self) -> None:
        data = self.ledger()
        data["evidence"][0]["observedAt"] = ""
        self.assert_failure(data, "requires nonempty observedAt")

    def test_rejects_evidence_with_missing_observed_at(self) -> None:
        data = self.ledger()
        del data["evidence"][0]["observedAt"]
        self.assert_failure(data, "requires nonempty observedAt")

    def test_rejects_evidence_with_invalid_calendar_date(self) -> None:
        data = self.ledger()
        data["evidence"][0]["observedAt"] = "2026-02-30"
        self.assert_failure(data, "observedAt must be an ISO calendar date")

    def test_rejects_path_a_with_missing_github_observation_date(self) -> None:
        data = self.ledger()
        del data["distribution"]["github"]["observedAt"]
        self.assert_failure(data, "Path A requires GitHub observation date")

    def test_rejects_path_a_with_invalid_github_observation_date(self) -> None:
        data = self.ledger()
        data["distribution"]["github"]["observedAt"] = "2026-02-30"
        self.assert_failure(data, "GitHub observation date must be an ISO calendar date")

    def test_rejects_different_valid_github_observation_date(self) -> None:
        data = self.ledger()
        data["distribution"]["github"]["observedAt"] = "2026-08-16"
        self.assert_failure(
            data,
            "Path A GitHub observedAt must exactly match 2026-08-17",
        )

    def test_rejects_missing_or_contradictory_github_limitation(self) -> None:
        mutations = (
            lambda github: github.pop("limitation"),
            lambda github: github.__setitem__(
                "limitation",
                "Current metadata proves no private distribution occurred.",
            ),
        )
        for mutate in mutations:
            with self.subTest(mutation=mutate):
                data = self.ledger()
                mutate(data["distribution"]["github"])
                self.assert_failure(
                    data,
                    "Path A GitHub limitation must exactly match the current constraint",
                )

    def test_ancestry_failure_reports_shallow_history_requirement(self) -> None:
        data = self.ledger()
        real_git_text = phase1_audit.git_text

        def fake_git_text(repo_root: Path, *args: str) -> str:
            if args[:2] == ("merge-base", "--is-ancestor"):
                raise RuntimeError("simulated missing ancestry")
            if args == ("rev-parse", "--is-shallow-repository"):
                return "true"
            return real_git_text(repo_root, *args)

        with mock.patch.object(phase1_audit, "git_text", side_effect=fake_git_text):
            failures = phase1_audit.validate_ledger(data, ROOT)
        self.assertTrue(any("writer commit is not an ancestor" in failure for failure in failures))
        self.assertTrue(any("full Git history is required" in failure for failure in failures))

    def test_malformed_collection_shapes_return_failures(self) -> None:
        mutations = {
            "writerEpochs": [None, "scalar", {"id": []}],
            "persistentResources": [None, "scalar", {"id": []}],
            "evidence": [None, "scalar", {"id": []}],
            "decision": {"path": "A", "rationaleEvidenceIds": None},
        }
        for field, value in mutations.items():
            data = self.ledger()
            if field == "decision":
                data[field] = value
            else:
                data[field] = value
            try:
                failures = phase1_audit.validate_ledger(data, ROOT)
            except (TypeError, AttributeError, KeyError):
                self.fail(f"validate_ledger raised for malformed {field}")
            self.assertTrue(failures, field)

    def test_wrong_collection_types_and_unhashable_references_return_failures(self) -> None:
        for field in ("writerEpochs", "persistentResources", "evidence"):
            for value in (None, {"id": "wrong-shape"}, "scalar"):
                data = self.ledger()
                data[field] = value
                self.assertTrue(phase1_audit.validate_ledger(data, ROOT), field)
        data = self.ledger()
        data["decision"]["rationaleEvidenceIds"] = [[], None, "scalar"]
        failures = phase1_audit.validate_ledger(data, ROOT)
        self.assertTrue(failures)

    def test_malformed_introduced_paths_return_failures(self) -> None:
        for value in (None, "scalar", {"path": "mapping"}, ["", 7, None]):
            data = self.ledger()
            data["writerEpochs"][0]["introducedPaths"] = value
            try:
                failures = phase1_audit.validate_ledger(data, ROOT)
            except (TypeError, AttributeError, KeyError):
                self.fail(f"validate_ledger raised for malformed introducedPaths: {value!r}")
            self.assertTrue(
                any("introducedPaths" in failure for failure in failures),
                failures,
            )


if __name__ == "__main__":
    unittest.main()
