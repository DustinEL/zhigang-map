import importlib.util
import json
import tempfile
import unittest
from pathlib import Path


SCRIPT = Path(__file__).with_name("validate-match-annotations.py")


def load_module():
    spec = importlib.util.spec_from_file_location("validate_match_annotations", SCRIPT)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class ValidateMatchAnnotationsTest(unittest.TestCase):
    def setUp(self):
        self.module = load_module()

    def test_reports_exact_agreement_and_weighted_kappa(self):
        labels_a = ["需先补关键能力", "接近可申请", "可申请实习/校招", "接近可申请"]
        labels_b = ["需先补关键能力", "接近可申请", "接近可申请", "可申请实习/校招"]

        report = self.module.annotation_agreement(labels_a, labels_b)

        self.assertEqual(0.5, report["exactAgreement"])
        self.assertAlmostEqual(0.5, report["weightedKappa"], places=8)

    def test_normalizes_english_and_chinese_labels_to_stable_keys(self):
        manifest = {
            "resumes": [
                {"resumeId": "r1", "split": "blind"},
                {"resumeId": "r2", "split": "blind"},
            ],
            "cases": [
                {"caseId": "c1", "resumeId": "r1", "roleId": 2},
                {"caseId": "c2", "resumeId": "r2", "roleId": 3},
                {"caseId": "c3", "resumeId": "r1", "roleId": 3},
            ],
        }
        annotation_a = [
            {"caseId": "c1", "label": "gap"},
            {"caseId": "c2", "label": "near"},
            {"caseId": "c3", "label": "ready"},
        ]
        annotation_b = [
            {"caseId": "c1", "label": "需先补关键能力"},
            {"caseId": "c2", "label": "接近可申请"},
            {"caseId": "c3", "label": "可申请实习/校招"},
        ]
        gold = {"cases": annotation_b}

        report = self.module.validate(
            manifest, annotation_a, annotation_b, gold,
            minimum_support=1, minimum_resumes=2, minimum_cases=3,
        )

        self.assertEqual(1.0, report["exactAgreement"])
        self.assertEqual(1.0, report["weightedKappa"])
        self.assertEqual({"gap": 1, "near": 1, "ready": 1}, report["classSupport"])

    def test_rejects_unknown_label_after_normalization(self):
        with self.assertRaisesRegex(self.module.ValidationError, "非法类别"):
            self.module.annotation_agreement(["gap"], ["unknown"])

    def test_rejects_candidate_duplicate_group_crossing_splits(self):
        manifest = {
            "resumes": [
                {"resumeId": "r1", "split": "validation", "duplicateGroup": "person-a"},
                {"resumeId": "r2", "split": "blind", "duplicateGroup": "person-a"},
            ],
            "cases": [
                {"caseId": "c1", "resumeId": "r1", "roleId": 2},
                {"caseId": "c2", "resumeId": "r2", "roleId": 2},
            ],
        }

        with self.assertRaisesRegex(self.module.ValidationError, "duplicateGroup"):
            self.module.validate_manifest_isolation(manifest)

    def test_validates_double_annotations_and_gold_support(self):
        manifest = {
            "resumes": [
                {"resumeId": "r1", "split": "blind", "contentSha256": "hash-r1"},
                {"resumeId": "r2", "split": "blind", "contentSha256": "hash-r2"},
            ],
            "cases": [
                {"caseId": "c1", "resumeId": "r1", "roleId": 2},
                {"caseId": "c2", "resumeId": "r2", "roleId": 3},
                {"caseId": "c3", "resumeId": "r1", "roleId": 3},
            ],
        }
        annotations = [
            {"caseId": "c1", "label": "需先补关键能力"},
            {"caseId": "c2", "label": "接近可申请"},
            {"caseId": "c3", "label": "可申请实习/校招"},
        ]
        gold = {"cases": annotations}

        report = self.module.validate(
            manifest, annotations, annotations, gold,
            minimum_support=1, minimum_resumes=2, minimum_cases=3,
        )

        self.assertTrue(report["passed"])
        self.assertEqual(1.0, report["exactAgreement"])
        self.assertEqual(1.0, report["weightedKappa"])
        self.assertEqual({
            "gap": 1,
            "near": 1,
            "ready": 1,
        }, report["classSupport"])

    def test_fails_gate_when_agreement_is_below_threshold(self):
        manifest = {
            "resumes": [{"resumeId": "r1", "split": "blind"}],
            "cases": [
                {"caseId": "c1", "resumeId": "r1", "roleId": 2},
                {"caseId": "c2", "resumeId": "r1", "roleId": 3},
            ],
        }
        annotation_a = [
            {"caseId": "c1", "label": "需先补关键能力"},
            {"caseId": "c2", "label": "可申请实习/校招"},
        ]
        annotation_b = [
            {"caseId": "c1", "label": "可申请实习/校招"},
            {"caseId": "c2", "label": "需先补关键能力"},
        ]

        report = self.module.validate(
            manifest, annotation_a, annotation_b, None,
            minimum_support=0, minimum_resumes=1, minimum_cases=2,
        )

        self.assertFalse(report["passed"])
        self.assertIn("exactAgreement<0.85", report["failures"])
        self.assertIn("weightedKappa<0.80", report["failures"])

    def test_fails_final_dataset_size_gate(self):
        manifest = {
            "resumes": [{"resumeId": "r1", "split": "blind"}],
            "cases": [{"caseId": "c1", "resumeId": "r1", "roleId": 2}],
        }
        annotations = [{"caseId": "c1", "label": "需先补关键能力"}]

        report = self.module.validate(
            manifest, annotations, annotations, None,
            minimum_support=0, minimum_resumes=30, minimum_cases=240,
        )

        self.assertFalse(report["passed"])
        self.assertIn("resumeCount<30", report["failures"])
        self.assertIn("caseCount<240", report["failures"])

    def test_accepts_frozen_gold_valid_cases_shape(self):
        manifest = {
            "resumes": [{"resumeId": "r1", "split": "blind"}],
            "cases": [{"caseId": "c1", "resumeId": "r1", "roleId": 2}],
        }
        annotations = [{"caseId": "c1", "label": "需先补关键能力"}]
        gold = {"validCases": annotations, "undecidableCases": []}

        report = self.module.validate(
            manifest, annotations, annotations, gold,
            minimum_support=0, minimum_resumes=1, minimum_cases=1,
        )

        self.assertTrue(report["passed"])

    def test_cli_defaults_match_thirty_resumes_across_seven_standard_roles(self):
        original_argv = list(__import__("sys").argv)
        try:
            __import__("sys").argv = [
                str(SCRIPT), "--manifest", "manifest.json",
                "--annotation-a", "a.json", "--annotation-b", "b.json",
                "--annotator-a", "a", "--annotator-b", "b",
                "--report", "report.json",
            ]
            args = self.module.parse_args()
        finally:
            __import__("sys").argv = original_argv

        self.assertEqual(210, args.minimum_cases)

    def test_strict_validation_rejects_gold_from_another_dataset(self):
        manifest = {
            "datasetId": "dataset-a", "datasetSha256": "a" * 64,
            "resumes": [{"resumeId": "r1", "split": "blind",
                         "candidateKey": "p1", "contentSha256": "c1"}],
            "cases": [{"caseId": "c1", "resumeId": "r1", "roleId": 2}],
        }
        annotations = [{"caseId": "c1", "label": "需先补关键能力"}]
        gold = {"datasetId": "dataset-b", "datasetSha256": "b" * 64,
                "cases": annotations}

        with self.assertRaisesRegex(self.module.ValidationError, "gold.*dataset"):
            self.module.validate(
                manifest, annotations, annotations, gold,
                minimum_support=30, minimum_resumes=30, minimum_cases=210,
                strict=True, annotator_a="a", annotator_b="b")

    def test_strict_validation_rejects_lowered_acceptance_gates(self):
        manifest = {
            "datasetId": "dataset-a", "datasetSha256": "a" * 64,
            "resumes": [{"resumeId": "r1", "split": "blind",
                         "candidateKey": "p1", "contentSha256": "c1"}],
            "cases": [{"caseId": "c1", "resumeId": "r1", "roleId": 2}],
        }
        annotations = [{"caseId": "c1", "label": "需先补关键能力"}]
        gold = {"datasetId": "dataset-a", "datasetSha256": "a" * 64,
                "cases": annotations}

        with self.assertRaisesRegex(self.module.ValidationError, "minimum"):
            self.module.validate(
                manifest, annotations, annotations, gold,
                minimum_support=0, minimum_resumes=1, minimum_cases=1,
                strict=True, annotator_a="a", annotator_b="b")

    def test_strict_validation_requires_exact_official_matrix(self):
        manifest = {
            "datasetId": "dataset-a", "datasetSha256": "a" * 64,
            "resumes": [{"resumeId": "r1", "split": "blind",
                         "candidateKey": "p1", "contentSha256": "c1"}],
            "cases": [{"caseId": "c1", "resumeId": "r1", "roleId": 13}],
        }
        annotations = [{"caseId": "c1", "label": "需先补关键能力"}]
        gold = {"datasetId": "dataset-a", "datasetSha256": "a" * 64,
                "cases": annotations}

        with self.assertRaisesRegex(self.module.ValidationError, "30.*210|标准岗位"):
            self.module.validate(
                manifest, annotations, annotations, gold,
                strict=True, annotator_a="a", annotator_b="b")

    def test_strict_annotation_requires_evidence_and_reason_codes(self):
        with self.assertRaisesRegex(self.module.ValidationError, "evidence"):
            self.module._labels_by_case(
                [{"caseId": "c1", "label": "需先补关键能力"}],
                {"c1"}, "annotation-a", strict=True)

    def test_strict_annotation_rejects_identity_mismatch(self):
        manifest = {
            "resumes": [{"resumeId": "r1", "split": "blind", "candidateKey": "p1",
                         "contentSha256": "hash"}],
            "cases": [{"caseId": "c1", "resumeId": "r1", "roleId": 2}],
        }
        annotations = [{"caseId": "c1", "resumeId": "r2", "roleId": 3,
                        "label": "需先补关键能力", "evidence": ["e"],
                        "reasonCodes": ["r"]}]
        with self.assertRaisesRegex(self.module.ValidationError, "身份"):
            self.module._validate_annotation_identity(
                annotations, "annotation-a", {"c1": manifest["cases"][0]})

    def test_strict_annotation_rejects_missing_identity(self):
        with self.assertRaisesRegex(self.module.ValidationError, "身份字段"):
            self.module._validate_annotation_identity(
                [{"caseId": "c1", "label": "需先补关键能力"}],
                "annotation-a", {"c1": {"resumeId": "r1", "roleId": 2}})


if __name__ == "__main__":
    unittest.main()
