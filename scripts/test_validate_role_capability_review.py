import csv
import importlib.util
import json
import tempfile
import unittest
from pathlib import Path


CREATE_SCRIPT = Path(__file__).with_name("create-role-capability-review.py")
VALIDATE_SCRIPT = Path(__file__).with_name("validate-role-capability-review.py")


def load(path, name):
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class ValidateRoleCapabilityReviewTest(unittest.TestCase):
    def setUp(self):
        self.creator = load(CREATE_SCRIPT, "create_role_capability_review")
        self.validator = load(VALIDATE_SCRIPT, "validate_role_capability_review")
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.source = self.root / "cards.json"
        self.output = self.root / "review"
        self.source.write_text(json.dumps({
            "schemaVersion": "role-capability-v2.0",
            "cards": [{
                "roleId": 2, "roleCode": "JAVA", "roleName": "Java 开发工程师", "version": "v2",
                "coreCapabilities": [{"id": "language", "alternatives": ["Java"],
                                      "nearEvidenceLevel": 2, "readyEvidenceLevel": 2, "blocking": True}],
                "transferableSkills": [], "bonusSkills": [], "sourceReferences": ["source"],
            }],
        }, ensure_ascii=False), encoding="utf-8")
        self.creator.create_review_package(self.source, self.output)

    def tearDown(self):
        self.temp.cleanup()

    def fill(self, filename, reviewer, decision="ACCEPT"):
        path = self.output / filename
        with path.open(encoding="utf-8-sig", newline="") as handle:
            rows = list(csv.DictReader(handle))
            fields = rows[0].keys()
        for row in rows:
            row["decision"] = decision
            row["evidenceOrReason"] = "岗位职责证据已核对"
            row["reviewerId"] = reviewer
            row["reviewedAt"] = "2026-08-14T12:00:00+08:00"
        with path.open("w", encoding="utf-8-sig", newline="") as handle:
            writer = csv.DictWriter(handle, fieldnames=fields)
            writer.writeheader()
            writer.writerows(rows)

    def test_passes_two_distinct_complete_agreeing_reviews(self):
        self.fill("reviewer-a.csv", "reviewer-a")
        self.fill("reviewer-b.csv", "reviewer-b")

        report = self.validator.validate_review_package(self.output, self.source)

        self.assertTrue(report["passed"])
        self.assertEqual(1.0, report["agreementRate"])
        self.assertEqual(["reviewer-a", "reviewer-b"], report["reviewerIds"])

    def test_rejects_same_reviewer_identity(self):
        self.fill("reviewer-a.csv", "same-person")
        self.fill("reviewer-b.csv", "same-person")

        report = self.validator.validate_review_package(self.output, self.source)

        self.assertFalse(report["passed"])
        self.assertIn("REVIEWERS_NOT_INDEPENDENT", report["failures"])

    def test_rejects_incomplete_reviews(self):
        report = self.validator.validate_review_package(self.output, self.source)

        self.assertFalse(report["passed"])
        self.assertIn("INCOMPLETE_REVIEW", report["failures"])


if __name__ == "__main__":
    unittest.main()
