import csv
import hashlib
import importlib.util
import json
import socket
import tempfile
import unittest
import zipfile
from pathlib import Path


RUNNER = Path(__file__).with_name("run-match-blind-predictions.py")
FINALIZER = Path(__file__).with_name("finalize-match-blind-intake.py")
AUDITOR = Path(__file__).with_name("audit-match-blind-leakage.py")


def load(path, name):
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class FakeClient:
    def __init__(self, state=None):
        self.calls = []
        self.state = state

    def upload(self, path, context):
        self.calls.append(("upload", path.name))
        return {"id": 101, "abilities": [{"skill": "Python", "level": 2}],
                "projects": ["RAG project"], "facts": [{"factType": "SKILL", "value": "Python"}]}

    def confirm(self, backend_resume_id):
        self.calls.append(("confirm", backend_resume_id))
        return {"id": backend_resume_id, "status": "CONFIRMED"}

    def match(self, backend_resume_id, case, context):
        self.calls.append(("match", case["caseId"]))
        return {"id": 501, "resumeId": backend_resume_id,
                "roleId": case["roleId"],
                "applicationState": self.state or "接近可申请",
                "scoringPolicyVersion": "ordered-capability-v2.4",
                "readiness": 62.5, "hardGates": [{"name": "技能"}],
                "dimensions": [{"name": "核心能力"}],
                "skillDetails": [{"skill": "Python"}]}


class RunMatchBlindPredictionsTest(unittest.TestCase):
    def setUp(self):
        self.runner = load(RUNNER, "run_match_blind_predictions")
        self.finalizer = load(FINALIZER, "finalize_match_blind_intake_for_runner")
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.intake = self.root / "intake"
        self.intake.mkdir()
        self.resume = self.root / "resume.docx"
        with zipfile.ZipFile(self.resume, "w") as archive:
            archive.writestr("word/document.xml",
                             '<w:document xmlns:w="x"><w:body><w:p><w:r><w:t>Python SQL ETL</w:t></w:r></w:p></w:body></w:document>')
        for relative in (
            "backend/target/zhigang-backend-1.0.0.jar",
            "backend/src/main/resources/matching/role-capability-cards-v2.json",
            "backend/src/main/java/com/zhigang/platform/career/ResumeEvidenceExtractor.java",
            "backend/src/main/java/com/zhigang/platform/career/MatchDecisionEngine.java",
        ):
            target = self.root / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_text(relative, encoding="utf-8")
        content_hash = hashlib.sha256(self.resume.read_bytes()).hexdigest()
        text_hash = self.finalizer.normalized_text_sha256("Python SQL ETL")
        registry = [{
            "resumeId": "FMB-001", "candidateKey": "p1", "sourcePath": "resume.docx",
            "contentSha256": content_hash, "normalizedTextSha256": text_hash,
            "duplicateGroup": "p1", "split": "blind", "consentOrLicense": "authorized",
            "piiRedacted": "true", "collectedBy": "collector", "collectedAt": "2026-08-19T00:00:00Z", "notes": "",
        }]
        cases = [{"caseId": "FMB-C-0001", "resumeId": "FMB-001", "roleId": "2",
                  "roleCode": "JAVA_ENGINEER", "roleName": "Java 开发工程师", "roleVersion": "v2"}]
        with (self.intake / "resume-registry.csv").open("w", encoding="utf-8-sig", newline="") as handle:
            writer = csv.DictWriter(handle, fieldnames=list(registry[0])); writer.writeheader(); writer.writerows(registry)
        with (self.intake / "case-manifest.csv").open("w", encoding="utf-8-sig", newline="") as handle:
            writer = csv.DictWriter(handle, fieldnames=list(cases[0])); writer.writeheader(); writer.writerows(cases)
        base = {"schemaVersion": "v1", "status": "INTAKE_INCOMPLETE", "containsLabels": False,
                "plannedResumeCount": 1, "plannedRoleCount": 1, "plannedCaseCount": 1,
                "roles": [{"roleId": 2, "roleCode": "JAVA_ENGINEER", "roleName": "Java 开发工程师", "roleVersion": "v2"}]}
        (self.intake / "intake-manifest.json").write_text(json.dumps(base, ensure_ascii=False), encoding="utf-8")
        history = self.root / "history"
        history.mkdir()
        historical_resume = history / "historical-resume.docx"
        with zipfile.ZipFile(historical_resume, "w") as archive:
            archive.writestr(
                "word/document.xml",
                '<w:document xmlns:w="x"><w:body><w:p><w:r><w:t>Historical Java QA</w:t></w:r></w:p></w:body></w:document>',
            )
        auditor = load(AUDITOR, "audit_match_blind_leakage_for_runner")
        leakage_report = auditor.audit(self.intake, self.root, [history])
        (self.intake / "leakage-audit.json").write_text(
            json.dumps(leakage_report, ensure_ascii=False, indent=2) + "\n",
            encoding="utf-8")
        self.manifest = self.finalizer.finalize_intake(self.intake, self.root)

    def tearDown(self):
        self.temp.cleanup()

    def resign_manifest(self):
        self.manifest.pop("datasetSha256", None)
        self.manifest["datasetSha256"] = self.runner.canonical_sha256(self.manifest)

    def test_executes_manifest_cases_once_and_returns_minimal_predictions(self):
        client = FakeClient()
        report = self.runner.run_predictions(self.manifest, self.root, client, official=False)

        self.assertEqual([("upload", "resume.docx"), ("confirm", 101),
                          ("match", "FMB-C-0001")], client.calls)
        self.assertEqual(1, report["caseCount"])
        self.assertEqual("FMB-C-0001", report["predictions"][0]["caseId"])
        self.assertNotIn("readiness", report["predictions"][0])
        self.assertNotIn("resumeAbilities", json.dumps(report, ensure_ascii=False))

    def test_diagnostic_mode_preserves_evidence_and_match_details(self):
        report = self.runner.run_predictions(
            self.manifest, self.root, FakeClient(), official=False, diagnostic_details=True)

        prediction = report["predictions"][0]
        self.assertEqual([{"skill": "Python", "level": 2}], prediction["diagnostic"]["resumeAbilities"])
        self.assertEqual(62.5, prediction["diagnostic"]["readiness"])
        self.assertEqual([{"skill": "Python"}], prediction["diagnostic"]["skillDetails"])

    def test_rejects_incomplete_manifest_before_client_call(self):
        client = FakeClient()
        self.manifest["status"] = "INTAKE_INCOMPLETE"

        with self.assertRaisesRegex(self.runner.PredictionError, "FROZEN_FOR_PREDICTION"):
            self.runner.run_predictions(self.manifest, self.root, client, official=False)
        self.assertEqual([], client.calls)

    def test_rejects_dataset_hash_mismatch_before_client_call(self):
        client = FakeClient()
        self.manifest["datasetId"] = "tampered"

        with self.assertRaisesRegex(self.runner.PredictionError, "datasetSha256"):
            self.runner.run_predictions(self.manifest, self.root, client, official=False)
        self.assertEqual([], client.calls)

    def test_rejects_artifact_hash_mismatch_before_client_call(self):
        client = FakeClient()
        self.manifest["artifacts"]["jarSha256"] = "0" * 64
        self.resign_manifest()

        with self.assertRaisesRegex(self.runner.PredictionError, "jarSha256"):
            self.runner.run_predictions(self.manifest, self.root, client, official=False)
        self.assertEqual([], client.calls)

    def test_rejects_leakage_audit_hash_mismatch_before_client_call(self):
        client = FakeClient()
        (self.intake / "leakage-audit.json").write_text("{}", encoding="utf-8")

        with self.assertRaisesRegex(self.runner.PredictionError, "leakage"):
            self.runner.run_predictions(self.manifest, self.root, client, official=False)
        self.assertEqual([], client.calls)

    def test_rejects_duplicate_case_before_client_call(self):
        client = FakeClient()
        self.manifest["cases"].append(dict(self.manifest["cases"][0]))
        self.manifest["plannedCaseCount"] = 2
        self.resign_manifest()

        with self.assertRaisesRegex(self.runner.PredictionError, "caseId"):
            self.runner.run_predictions(self.manifest, self.root, client, official=False)
        self.assertEqual([], client.calls)

    def test_rejects_missing_case_before_client_call(self):
        client = FakeClient()
        self.manifest["cases"] = []
        self.manifest["plannedCaseCount"] = 0
        self.resign_manifest()

        with self.assertRaisesRegex(self.runner.PredictionError, "cases"):
            self.runner.run_predictions(self.manifest, self.root, client, official=False)
        self.assertEqual([], client.calls)

    def test_rejects_invalid_prediction_state(self):
        client = FakeClient(state="INVALID")

        with self.assertRaisesRegex(self.runner.PredictionError, "FMB-C-0001"):
            self.runner.run_predictions(self.manifest, self.root, client, official=False)
        self.assertEqual("match", client.calls[-1][0])

    def test_atomic_write_refuses_to_overwrite_existing_output(self):
        output = self.root / "predictions.json"
        output.write_text("original", encoding="utf-8")

        with self.assertRaisesRegex(self.runner.PredictionError, "predictions.json"):
            self.runner.atomic_write_new(output, {"replacement": True})
        self.assertEqual("original", output.read_text(encoding="utf-8"))

    def test_rejects_missing_dataset_id_before_client_call(self):
        client = FakeClient()
        self.manifest.pop("datasetId")
        self.resign_manifest()

        with self.assertRaisesRegex(self.runner.PredictionError, "datasetId"):
            self.runner.run_predictions(self.manifest, self.root, client, official=False)
        self.assertEqual([], client.calls)

    def test_rejects_label_field_in_manifest_before_client_call(self):
        client = FakeClient()
        self.manifest["cases"][0]["goldLabel"] = "需先补关键能力"
        self.resign_manifest()

        with self.assertRaisesRegex(self.runner.PredictionError, "label"):
            self.runner.run_predictions(self.manifest, self.root, client, official=False)
        self.assertEqual([], client.calls)

    def test_rejects_empty_scoring_policy_version(self):
        class EmptyPolicyClient(FakeClient):
            def match(self, backend_resume_id, case, context):
                result = super().match(backend_resume_id, case, context)
                result["scoringPolicyVersion"] = ""
                return result

        with self.assertRaisesRegex(self.runner.PredictionError, "scoringPolicyVersion"):
            self.runner.run_predictions(
                self.manifest, self.root, EmptyPolicyClient(), official=False)

    def test_rejects_confirmation_without_confirmed_status(self):
        class UnconfirmedClient(FakeClient):
            def confirm(self, backend_resume_id):
                self.calls.append(("confirm", backend_resume_id))
                return {"id": backend_resume_id, "status": "DRAFT"}

        with self.assertRaisesRegex(self.runner.PredictionError, "FMB-001"):
            self.runner.run_predictions(
                self.manifest, self.root, UnconfirmedClient(), official=False)

    def test_rejects_port_that_is_already_in_use(self):
        listener = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        listener.bind(("127.0.0.1", 0))
        listener.listen(1)
        try:
            port = listener.getsockname()[1]
            with self.assertRaisesRegex(self.runner.PredictionError, "port"):
                self.runner.ensure_port_available(port)
        finally:
            listener.close()

    def test_frozen_snapshot_is_independent_of_source_changes(self):
        snapshot = self.root / "snapshot"
        self.runner.create_frozen_snapshot(
            self.manifest, self.root, snapshot, official=False)
        original_bytes = self.resume.read_bytes()

        self.resume.write_bytes(b"changed after preflight")
        report = self.runner.run_predictions(
            self.manifest, snapshot, FakeClient(), official=False)

        self.assertEqual(1, report["caseCount"])
        self.assertEqual(original_bytes, (snapshot / "resume.docx").read_bytes())

    def test_prediction_report_has_runner_proof_fields(self):
        report = self.runner.run_predictions(
            self.manifest, self.root, FakeClient(), official=False)

        self.assertEqual("match-blind-predictions-v1.0", report["schemaVersion"])
        self.assertTrue(report["scoringPolicyVersions"])
        self.assertEqual(1, len(report["scoringPolicyVersions"]))


if __name__ == "__main__":
    unittest.main()
