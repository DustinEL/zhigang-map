import csv
import importlib.util
import json
import tempfile
import unittest
from pathlib import Path


SCRIPT = Path(__file__).with_name("create-role-capability-review.py")


def load_module():
    spec = importlib.util.spec_from_file_location("create_role_capability_review", SCRIPT)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class CreateRoleCapabilityReviewTest(unittest.TestCase):
    def test_generates_two_independent_review_sheets_and_manifest(self):
        module = load_module()
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = root / "cards.json"
            output = root / "review"
            source.write_text(json.dumps({
                "schemaVersion": "role-capability-v2.0",
                "cards": [{
                    "roleId": 2,
                    "roleCode": "JAVA_ENGINEER",
                    "roleName": "Java 开发工程师",
                    "version": "java-v2.0",
                    "coreCapabilities": [
                        {"id": "language", "alternatives": ["Java"],
                         "nearEvidenceLevel": 2, "readyEvidenceLevel": 2, "blocking": True},
                        {"id": "backend", "alternatives": ["Spring Boot"],
                         "nearEvidenceLevel": 1, "readyEvidenceLevel": 2, "blocking": True},
                    ],
                    "transferableSkills": ["Python"],
                    "bonusSkills": ["Kafka"],
                    "sourceReferences": ["source-1"],
                    "reviewerIds": ["engineering-baseline"],
                    "reviewStatus": "INDEPENDENT_REVIEW_REQUIRED",
                }],
            }, ensure_ascii=False), encoding="utf-8")

            manifest = module.create_review_package(source, output)

            self.assertEqual(2, manifest["coreCapabilityCount"])
            self.assertEqual(1, manifest["roleCount"])
            self.assertEqual("DRAFT_REVIEW_PACKAGE", manifest["status"])
            for name in ("reviewer-a.csv", "reviewer-b.csv", "adjudication.csv",
                         "review-guidelines.md", "manifest.json"):
                self.assertTrue((output / name).exists(), name)
            with (output / "reviewer-a.csv").open(encoding="utf-8-sig", newline="") as handle:
                rows = list(csv.DictReader(handle))
            self.assertEqual(4, len(rows))
            self.assertEqual({"CORE", "TRANSFERABLE", "BONUS"}, {row["itemType"] for row in rows})
            self.assertTrue(all(not row["reviewerId"] for row in rows))


if __name__ == "__main__":
    unittest.main()
