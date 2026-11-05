import csv
import hashlib
import importlib.util
import json
import re
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


SCRIPT = Path(__file__).with_name("audit-match-blind-leakage.py")


def load_module():
    spec = importlib.util.spec_from_file_location("audit_match_blind_leakage", SCRIPT)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def normalized_sha(text):
    normalized = re.sub(r"[^0-9a-z\u4e00-\u9fff]+", "", text.casefold())
    return hashlib.sha256(normalized.encode("utf-8")).hexdigest()


class AuditMatchBlindLeakageTest(unittest.TestCase):
    def setUp(self):
        self.module = load_module()
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.intake = self.root / "intake"
        self.history = self.root / "history"
        self.intake.mkdir()
        self.history.mkdir()
        (self.history / "history-support.txt").write_text(
            "Historical resume support record", encoding="utf-8")

    def tearDown(self):
        self.temp.cleanup()

    def write_registry(self, directory, rows):
        path = directory / "resume-registry.csv"
        fields = [
            "resumeId", "candidateKey", "sourcePath", "contentSha256",
            "normalizedTextSha256", "duplicateGroup", "split",
            "consentOrLicense", "piiRedacted", "collectedBy", "collectedAt",
            "notes",
        ]
        with path.open("w", encoding="utf-8-sig", newline="") as handle:
            writer = csv.DictWriter(handle, fieldnames=fields)
            writer.writeheader()
            writer.writerows(rows)

    def register_current(self, text, candidate_key="candidate-current"):
        resume = self.root / "current.txt"
        resume.write_text(text, encoding="utf-8")
        self.write_registry(self.intake, [{
            "resumeId": "FMB-001",
            "candidateKey": candidate_key,
            "sourcePath": "current.txt",
            "contentSha256": hashlib.sha256(resume.read_bytes()).hexdigest(),
            "normalizedTextSha256": normalized_sha(text),
            "duplicateGroup": candidate_key,
            "split": "blind",
            "consentOrLicense": "authorized",
            "piiRedacted": "true",
            "collectedBy": "collector-a",
            "collectedAt": "2026-08-20T00:00:00Z",
            "notes": "",
        }])
        return resume

    def finding_types(self, report):
        return {finding["type"] for finding in report["findings"]}

    def test_detects_exact_byte_copy_in_history(self):
        current = self.register_current("Java Spring SQL production project")
        (self.history / "old.txt").write_bytes(current.read_bytes())

        report = self.module.audit(self.intake, self.root, [self.history])

        self.assertEqual("FAIL", report["status"])
        self.assertIn("EXACT_BYTE_SHA", self.finding_types(report))

    def test_detects_same_normalized_text_with_different_bytes(self):
        self.register_current("Python, SQL and ETL!")
        (self.history / "old.md").write_text("PYTHON SQL---AND etl", encoding="utf-8")

        report = self.module.audit(self.intake, self.root, [self.history])

        self.assertEqual("FAIL", report["status"])
        self.assertNotIn("EXACT_BYTE_SHA", self.finding_types(report))
        self.assertIn("NORMALIZED_TEXT_SHA", self.finding_types(report))

    def test_detects_high_shingle_jaccard_near_duplicate(self):
        original = "".join(chr(0x4E00 + index) for index in range(120))
        changed = original[:-1] + chr(0x4E00 + 200)
        self.register_current(original)
        (self.history / "old.txt").write_text(changed, encoding="utf-8")

        report = self.module.audit(
            self.intake, self.root, [self.history], threshold=0.90)

        near = [item for item in report["findings"] if item["type"] == "NEAR_TEXT"]
        self.assertEqual(1, len(near))
        self.assertGreaterEqual(near[0]["similarity"], 0.90)
        self.assertLess(near[0]["similarity"], 1.0)

    def test_detects_candidate_key_from_historical_registry(self):
        self.register_current("Unique current resume body", candidate_key="person-007")
        self.write_registry(self.history, [{
            "resumeId": "OLD-001", "candidateKey": "person-007",
            "sourcePath": "missing.txt", "contentSha256": "",
            "normalizedTextSha256": "", "duplicateGroup": "person-007",
            "split": "development", "consentOrLicense": "authorized",
            "piiRedacted": "true", "collectedBy": "collector-b",
            "collectedAt": "2026-01-01T00:00:00Z", "notes": "",
        }])

        report = self.module.audit(self.intake, self.root, [self.history])

        self.assertIn("CANDIDATE_KEY", self.finding_types(report))

    def test_passes_unique_intake_and_is_deterministic(self):
        current = self.register_current("Java backend and distributed systems")
        (self.history / "old.txt").write_text(
            "Visual design typography branding portfolio", encoding="utf-8")
        # A history root may overlap the intake root; the current file is not leakage.
        first = self.module.audit(self.intake, self.root, [self.history, current.parent])
        second = self.module.audit(self.intake, self.root, [self.history, current.parent])

        self.assertEqual(first, second)
        self.assertEqual("match-blind-leakage-audit-v1.0", first["schemaVersion"])
        self.assertEqual("PASS", first["status"])
        self.assertEqual([], first["findings"])
        self.assertEqual(64, len(first["intakeFingerprint"]))
        self.assertEqual(1, first["counts"]["intakeResumes"])
        self.assertEqual(
            sorted({self.history.resolve().as_posix(), current.parent.resolve().as_posix()},
                   key=str.casefold),
            first["historyRoots"],
        )

    def test_rejects_registered_hash_that_does_not_match_source(self):
        self.register_current("Verified body")
        with (self.intake / "resume-registry.csv").open(
                "r", encoding="utf-8-sig", newline="") as handle:
            registry = list(csv.DictReader(handle))
        registry[0]["contentSha256"] = "0" * 64
        self.write_registry(self.intake, registry)

        with self.assertRaisesRegex(self.module.AuditError, "contentSha256"):
            self.module.audit(self.intake, self.root, [self.history])

    def test_rejects_non_formal_threshold(self):
        self.register_current("Threshold must be fixed")
        with self.assertRaisesRegex(self.module.AuditError, "0.90"):
            self.module.audit(self.intake, self.root, [self.history], threshold=0.89)

    def test_rejects_empty_history_root(self):
        self.register_current("History support is required")
        empty = self.root / "empty-history"
        empty.mkdir()
        with self.assertRaisesRegex(self.module.AuditError, "supported file"):
            self.module.audit(self.intake, self.root, [empty])

    def test_reports_history_root_file_snapshots(self):
        self.register_current("Unique current text")
        report = self.module.audit(self.intake, self.root, [self.history])
        details = report["historyRootDetails"]
        self.assertEqual(1, len(details))
        self.assertEqual(1, details[0]["fileCount"])
        self.assertEqual(1, len(details[0]["contentSnapshots"]))
        self.assertEqual("history-support.txt",
                         Path(details[0]["contentSnapshots"][0]["path"]).name)

    def test_skips_unreadable_non_resume_history_files_and_reports_them(self):
        self.register_current("Unique current text")
        unreadable = self.history / "runtime-log.txt"
        unreadable.write_bytes(bytes([255, 254]) + b" not a resume text file")

        report = self.module.audit(self.intake, self.root, [self.history])

        self.assertEqual("PASS", report["status"])
        self.assertEqual(1, report["counts"]["historyFiles"])
        self.assertEqual(1, report["counts"]["unsupportedHistoryFiles"])
        self.assertEqual(
            "runtime-log.txt",
            Path(report["historyRootDetails"][0]["unsupportedFiles"][0]["path"]).name,
        )

    def test_detects_internal_candidate_group_and_near_duplicates(self):
        first = self.root / "first.txt"
        second = self.root / "second.txt"
        text = "".join(chr(0x4E00 + index) for index in range(120))
        first.write_text(text, encoding="utf-8")
        second.write_text(text[:-1] + chr(0x4E00 + 200), encoding="utf-8")
        rows = []
        for index, path in enumerate((first, second), 1):
            body = path.read_text(encoding="utf-8")
            rows.append({
                "resumeId": f"FMB-{index:03d}",
                "candidateKey": "same-person",
                "sourcePath": path.name,
                "contentSha256": hashlib.sha256(path.read_bytes()).hexdigest(),
                "normalizedTextSha256": normalized_sha(body),
                "duplicateGroup": "same-group",
                "split": "blind", "consentOrLicense": "authorized",
                "piiRedacted": "true", "collectedBy": "collector-a",
                "collectedAt": "2026-08-20T00:00:00Z", "notes": "",
            })
        self.write_registry(self.intake, rows)
        report = self.module.audit(self.intake, self.root, [self.history])
        types = self.finding_types(report)
        self.assertEqual("FAIL", report["status"])
        self.assertIn("INTERNAL_CANDIDATE_KEY", types)
        self.assertIn("INTERNAL_DUPLICATE_GROUP", types)
        self.assertIn("INTERNAL_NEAR_TEXT", types)

    def test_cli_writes_requested_report_and_returns_nonzero_on_leakage(self):
        current = self.register_current("Same resume")
        (self.history / "old.txt").write_bytes(current.read_bytes())
        output = self.root / "report.json"

        result = subprocess.run([
            sys.executable, str(SCRIPT),
            "--intake", str(self.intake),
            "--root", str(self.root),
            "--history-root", str(self.history),
            "--output", str(output),
        ], check=False, capture_output=True, text=True)

        self.assertEqual(1, result.returncode)
        self.assertEqual("FAIL", json.loads(output.read_text(encoding="utf-8"))["status"])


if __name__ == "__main__":
    unittest.main()
