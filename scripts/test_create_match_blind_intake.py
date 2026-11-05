import csv
import importlib.util
import json
import tempfile
import unittest
from pathlib import Path


SCRIPT = Path(__file__).with_name("create-match-blind-intake.py")


def load_module():
    spec = importlib.util.spec_from_file_location("create_match_blind_intake", SCRIPT)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class CreateMatchBlindIntakeTest(unittest.TestCase):
    def test_creates_thirty_resume_slots_and_240_unlabeled_cases(self):
        module = load_module()
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            cards = root / "cards.json"
            output = root / "intake"
            cards.write_text(json.dumps({
                "schemaVersion": "v2",
                "cards": [{"roleId": role_id, "roleCode": f"R{role_id}", "roleName": f"岗位{role_id}", "version": "v2"}
                          for role_id in [2, 3, 4, 5, 7, 9, 12, 13]],
            }, ensure_ascii=False), encoding="utf-8")

            manifest = module.create_intake(cards, output, resume_count=30)

            self.assertEqual(30, manifest["plannedResumeCount"])
            self.assertEqual(240, manifest["plannedCaseCount"])
            with (output / "resume-registry.csv").open(encoding="utf-8-sig", newline="") as handle:
                resumes = list(csv.DictReader(handle))
            with (output / "case-manifest.csv").open(encoding="utf-8-sig", newline="") as handle:
                cases = list(csv.DictReader(handle))
            self.assertEqual(30, len(resumes))
            self.assertEqual(240, len(cases))
            self.assertNotIn("label", cases[0])
            annotations = json.loads((output / "annotation-a.json").read_text(encoding="utf-8"))
            self.assertEqual(240, len(annotations))
            self.assertTrue(all(item["label"] == "" for item in annotations))

    def test_creates_standard_seven_role_intake_with_210_unlabeled_cases(self):
        module = load_module()
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            cards = root / "cards.json"
            output = root / "intake"
            cards.write_text(json.dumps({
                "schemaVersion": "v2",
                "cards": [{"roleId": role_id, "roleCode": f"R{role_id}", "roleName": f"岗位{role_id}", "version": "v2"}
                          for role_id in [2, 3, 4, 5, 7, 9, 12, 13]],
            }, ensure_ascii=False), encoding="utf-8")

            manifest = module.create_intake(cards, output, resume_count=30,
                                            role_ids=[2, 3, 4, 5, 7, 9, 12])

            self.assertEqual(7, manifest["plannedRoleCount"])
            self.assertEqual(210, manifest["plannedCaseCount"])
            with (output / "case-manifest.csv").open(encoding="utf-8-sig", newline="") as handle:
                cases = list(csv.DictReader(handle))
            self.assertEqual({"2", "3", "4", "5", "7", "9", "12"},
                             {case["roleId"] for case in cases})
            self.assertNotIn("label", cases[0])


if __name__ == "__main__":
    unittest.main()
