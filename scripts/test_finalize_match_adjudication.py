import importlib.util
import unittest
from pathlib import Path


SCRIPT = Path(__file__).with_name("finalize-match-adjudication.py")


def load_module():
    spec = importlib.util.spec_from_file_location("finalize_match_adjudication", SCRIPT)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class FinalizeMatchAdjudicationTest(unittest.TestCase):
    def setUp(self):
        self.module = load_module()
        self.manifest = {
            "datasetId": "dataset-1",
            "datasetSha256": "dataset-sha",
            "cases": [
                {"caseId": "c1", "resumeId": "r1", "roleId": 2,
                 "roleCode": "JAVA", "roleName": "Java", "roleVersion": "v1"},
                {"caseId": "c2", "resumeId": "r1", "roleId": 3,
                 "roleCode": "AI", "roleName": "AI", "roleVersion": "v1"},
            ],
        }
        self.annotation_a = [
            {"caseId": "c1", "resumeId": "r1", "roleId": 2,
             "label": "需先补关键能力", "evidence": ["A证据"],
             "reasonCodes": ["A理由"]},
            {"caseId": "c2", "resumeId": "r1", "roleId": 3,
             "label": "接近可申请", "evidence": ["A分歧证据"],
             "reasonCodes": ["A分歧理由"]},
        ]
        self.annotation_b = [
            {"caseId": "c1", "resumeId": "r1", "roleId": 2,
             "label": "需先补关键能力", "evidence": ["B证据"],
             "reasonCodes": ["B理由"]},
            {"caseId": "c2", "resumeId": "r1", "roleId": 3,
             "label": "可申请实习/校招", "evidence": ["B分歧证据"],
             "reasonCodes": ["B分歧理由"]},
        ]
        self.adjudication = {
            "datasetId": "dataset-1",
            "datasetSha256": "dataset-sha",
            "manifestSha256": "manifest-sha",
            "annotationASha256": "a-sha",
            "annotationBSha256": "b-sha",
            "items": [{
                "caseId": "c2", "resumeId": "r1", "roleId": 3,
                "roleCode": "AI", "roleName": "AI",
                "adjudicatedLabel": "可申请实习/校招",
                "adjudicatedEvidence": ["裁决证据"],
                "adjudicationReason": "裁决理由",
            }],
        }

    def test_builds_gold_from_agreements_and_exact_adjudications(self):
        gold = self.module.build_final_gold(
            self.manifest, self.annotation_a, self.annotation_b,
            self.adjudication, "manifest-sha", "a-sha", "b-sha", "adj-sha")

        self.assertEqual("dataset-1", gold["datasetId"])
        self.assertEqual("adj-sha", gold["adjudicationSha256"])
        self.assertEqual([], gold["undecidableCases"])
        self.assertEqual(2, len(gold["cases"]))
        self.assertEqual("需先补关键能力", gold["cases"][0]["label"])
        self.assertEqual(["A证据", "B证据"], gold["cases"][0]["evidence"])
        self.assertEqual("agreement", gold["cases"][0]["resolutionSource"])
        self.assertEqual("可申请实习/校招", gold["cases"][1]["label"])
        self.assertEqual(["裁决证据"], gold["cases"][1]["evidence"])
        self.assertEqual(["裁决理由"], gold["cases"][1]["reasonCodes"])
        self.assertEqual("adjudication", gold["cases"][1]["resolutionSource"])
        self.assertEqual("v1", gold["cases"][1]["roleVersion"])

    def test_rejects_missing_adjudication_for_a_disagreement(self):
        self.adjudication["items"] = []

        with self.assertRaisesRegex(self.module.FinalizationError, "分歧集合"):
            self.module.build_final_gold(
                self.manifest, self.annotation_a, self.annotation_b,
                self.adjudication, "manifest-sha", "a-sha", "b-sha", "adj-sha")

    def test_rejects_hash_binding_mismatch(self):
        self.adjudication["annotationASha256"] = "other"

        with self.assertRaisesRegex(self.module.FinalizationError, "哈希绑定"):
            self.module.build_final_gold(
                self.manifest, self.annotation_a, self.annotation_b,
                self.adjudication, "manifest-sha", "a-sha", "b-sha", "adj-sha")


if __name__ == "__main__":
    unittest.main()
