import csv
import hashlib
import importlib.util
import json
import tempfile
import unittest
from pathlib import Path


SCRIPT = Path(__file__).with_name("finalize-match-blind-intake.py")
AUDITOR = Path(__file__).with_name("audit-match-blind-leakage.py")


def load_module():
    spec = importlib.util.spec_from_file_location("finalize_match_blind_intake", SCRIPT)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class FinalizeMatchBlindIntakeTest(unittest.TestCase):
    def setUp(self):
        self.module = load_module()
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.intake = self.root / "intake"
        self.intake.mkdir()
        self.resume = self.root / "resume.txt"
        self.resume.write_text("Python SQL ETL 项目", encoding="utf-8")
        content_hash = hashlib.sha256(self.resume.read_bytes()).hexdigest()
        normalized_hash = self.module.normalized_text_sha256("Python SQL ETL 项目")
        self.registry = [{
            "resumeId": "FMB-001", "candidateKey": "person-001",
            "sourcePath": "resume.txt", "contentSha256": content_hash,
            "normalizedTextSha256": normalized_hash, "duplicateGroup": "person-001",
            "split": "blind", "consentOrLicense": "authorized",
            "piiRedacted": "true", "collectedBy": "collector-a",
            "collectedAt": "2026-08-19T00:00:00Z", "notes": "",
        }]
        self.cases = [{
            "caseId": "FMB-C-0001", "resumeId": "FMB-001", "roleId": "2",
            "roleCode": "JAVA_ENGINEER", "roleName": "Java 开发工程师", "roleVersion": "v2",
        }]
        self.base = {
            "schemaVersion": "match-final-blind-intake-v1.0",
            "status": "INTAKE_INCOMPLETE", "plannedResumeCount": 1,
            "plannedRoleCount": 1, "plannedCaseCount": 1,
            "containsLabels": False,
            "roles": [{"roleId": 2, "roleCode": "JAVA_ENGINEER",
                       "roleName": "Java 开发工程师", "roleVersion": "v2"}],
        }
        for relative in (
            "backend/target/zhigang-backend-1.0.0.jar",
            "backend/src/main/resources/matching/role-capability-cards-v2.json",
            "backend/src/main/java/com/zhigang/platform/career/ResumeEvidenceExtractor.java",
            "backend/src/main/java/com/zhigang/platform/career/MatchDecisionEngine.java",
        ):
            artifact = self.root / relative
            artifact.parent.mkdir(parents=True, exist_ok=True)
            artifact.write_text(relative, encoding="utf-8")
        (self.intake / "intake-manifest.json").write_text(
            json.dumps(self.base, ensure_ascii=False), encoding="utf-8")
        self.write_csv("resume-registry.csv", self.registry)
        self.write_csv("case-manifest.csv", self.cases)
        self.history = self.root / "history"
        self.history.mkdir()
        (self.history / "history-support.txt").write_text(
            "Historical resume support record", encoding="utf-8")
        auditor = self.load(AUDITOR, "audit_match_blind_leakage_for_finalizer")
        leakage_report = auditor.audit(self.intake, self.root, [self.history])
        (self.intake / "leakage-audit.json").write_text(
            json.dumps(leakage_report, ensure_ascii=False, indent=2) + "\n",
            encoding="utf-8")

    def tearDown(self):
        self.temp.cleanup()

    def write_csv(self, name, rows):
        with (self.intake / name).open("w", encoding="utf-8-sig", newline="") as handle:
            writer = csv.DictWriter(handle, fieldnames=list(rows[0]))
            writer.writeheader()
            writer.writerows(rows)

    @staticmethod
    def load(path, name):
        spec = importlib.util.spec_from_file_location(name, path)
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        return module

    def test_seals_verified_files_and_case_contract(self):
        sealed = self.module.finalize_intake(self.intake, self.root)

        self.assertEqual("FROZEN_FOR_PREDICTION", sealed["status"])
        self.assertEqual("match-final-blind-intake-v2.0", sealed["schemaVersion"])
        self.assertEqual("match-final-blind-1x1-v1", sealed["datasetId"])
        self.assertEqual(1, len(sealed["resumes"]))
        self.assertEqual(1, len(sealed["cases"]))
        self.assertEqual(64, len(sealed["datasetSha256"]))
        self.assertFalse(sealed["containsLabels"])
        self.assertEqual("resume.txt", sealed["resumes"][0]["sourcePath"])
        self.assertEqual(4, len(sealed["artifacts"]))
        self.assertEqual(64, len(sealed["leakageAudit"]["reportSha256"]))
        self.assertEqual("PASS", sealed["leakageAudit"]["status"])

    def test_rejects_registry_hash_that_does_not_match_file(self):
        self.registry[0]["contentSha256"] = "0" * 64
        self.write_csv("resume-registry.csv", self.registry)

        with self.assertRaisesRegex(self.module.FinalizationError, "contentSha256"):
            self.module.finalize_intake(self.intake, self.root)

    def test_rejects_absolute_resume_path(self):
        self.registry[0]["sourcePath"] = str(self.resume)
        self.write_csv("resume-registry.csv", self.registry)

        with self.assertRaisesRegex(self.module.FinalizationError, "相对路径"):
            self.module.finalize_intake(self.intake, self.root)

    def test_rejects_missing_workspace_root(self):
        with self.assertRaisesRegex(self.module.FinalizationError, "workspace root"):
            self.module.finalize_intake(self.intake, self.root / "missing-root")

    def test_rejects_non_formal_leakage_threshold(self):
        report_path = self.intake / "leakage-audit.json"
        report = json.loads(report_path.read_text(encoding="utf-8"))
        report["threshold"] = 0.89
        report_path.write_text(json.dumps(report), encoding="utf-8")

        with self.assertRaisesRegex(self.module.FinalizationError, "threshold"):
            self.module.finalize_intake(self.intake, self.root)


    def test_rejects_failed_leakage_audit(self):
        report_path = self.intake / "leakage-audit.json"
        report = json.loads(report_path.read_text(encoding="utf-8"))
        report["status"] = "FAIL"
        report["findings"] = [{"type": "EXACT_BYTE_SHA"}]
        report_path.write_text(json.dumps(report), encoding="utf-8")

        with self.assertRaisesRegex(self.module.FinalizationError, "leakage"):
            self.module.finalize_intake(self.intake, self.root)

    def test_rejects_leakage_audit_for_different_intake(self):
        report_path = self.intake / "leakage-audit.json"
        report = json.loads(report_path.read_text(encoding="utf-8"))
        report["intakeFingerprint"] = "0" * 64
        report_path.write_text(json.dumps(report), encoding="utf-8")

        with self.assertRaisesRegex(self.module.FinalizationError, "fingerprint"):
            self.module.finalize_intake(self.intake, self.root)

    def test_rejects_handwritten_pass_without_audit_binding(self):
        report_path = self.intake / "leakage-audit.json"
        report = json.loads(report_path.read_text(encoding="utf-8"))
        report.pop("auditProgramSha256", None)
        report["threshold"] = 0.90
        report_path.write_text(json.dumps(report), encoding="utf-8")

        with self.assertRaisesRegex(self.module.FinalizationError, "audit program"):
            self.module.finalize_intake(self.intake, self.root)

    def test_rejects_audit_with_empty_history_coverage(self):
        report_path = self.intake / "leakage-audit.json"
        report = json.loads(report_path.read_text(encoding="utf-8"))
        report["historyRootDetails"] = [{
            "root": str(self.history), "fileCount": 0, "contentSnapshots": []
        }]
        report["counts"]["historyFiles"] = 0
        report_path.write_text(json.dumps(report), encoding="utf-8")

        with self.assertRaisesRegex(self.module.FinalizationError, "history"):
            self.module.finalize_intake(self.intake, self.root)

    def test_rejects_internal_duplicate_findings(self):
        report_path = self.intake / "leakage-audit.json"
        report = json.loads(report_path.read_text(encoding="utf-8"))
        report["findings"] = [{"type": "INTERNAL_DUPLICATE_GROUP"}]
        report["counts"]["findingCount"] = 1
        report["counts"]["internalFindingCount"] = 1
        report["status"] = "PASS"
        report_path.write_text(json.dumps(report), encoding="utf-8")

        with self.assertRaisesRegex(self.module.FinalizationError, "internal"):
            self.module.finalize_intake(self.intake, self.root)

    def test_rejects_case_role_not_in_manifest(self):
        self.cases[0]["roleId"] = "99"
        self.write_csv("case-manifest.csv", self.cases)

        with self.assertRaisesRegex(self.module.FinalizationError, "role"):
            self.module.finalize_intake(self.intake, self.root)

    def test_rejects_label_column_in_case_manifest(self):
        self.cases[0]["goldLabel"] = "需先补关键能力"
        self.write_csv("case-manifest.csv", self.cases)

        with self.assertRaisesRegex(self.module.FinalizationError, "label"):
            self.module.finalize_intake(self.intake, self.root)


if __name__ == "__main__":
    unittest.main()
