import importlib.util
import hashlib
import json
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace


SCRIPT = Path(__file__).with_name("evaluate-match-blind.py")


def load_module():
    spec = importlib.util.spec_from_file_location("evaluate_match_blind", SCRIPT)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class EvaluateMatchBlindTest(unittest.TestCase):
    def setUp(self):
        self.module = load_module()
        self.manifest = {
            "datasetId": "dataset-1",
            "status": "FROZEN_FOR_PREDICTION",
            "containsLabels": False,
            "artifacts": {"jarSha256": "a" * 64},
            "cases": [
                {"caseId": "c1", "resumeId": "r1", "roleId": 2,
                 "roleCode": "R2", "roleName": "岗位 2", "roleVersion": "v1"},
                {"caseId": "c2", "resumeId": "r1", "roleId": 3,
                 "roleCode": "R3", "roleName": "岗位 3", "roleVersion": "v1"},
                {"caseId": "c3", "resumeId": "r2", "roleId": 2,
                 "roleCode": "R2", "roleName": "岗位 2", "roleVersion": "v1"},
            ]
        }
        self.manifest["datasetSha256"] = self.canonical_sha256(self.manifest)
        self.gold = {"datasetId": "dataset-1", "datasetSha256": self.manifest["datasetSha256"], "cases": [
            self.item("c1", "需先补关键能力"),
            self.item("c2", "接近可申请"),
            self.item("c3", "可申请实习/校招"),
        ]}

    @staticmethod
    def canonical_sha256(value):
        return hashlib.sha256(json.dumps(
            value, ensure_ascii=False, sort_keys=True,
            separators=(",", ":")).encode("utf-8")).hexdigest()

    def predictions(self, cases, **overrides):
        result = {
            "datasetId": "dataset-1",
            "datasetSha256": self.manifest["datasetSha256"],
            "artifactHashes": dict(self.manifest["artifacts"]),
            "predictions": cases,
        }
        result.update(overrides)
        return result

    def item(self, case_id, label, **overrides):
        case = next(case for case in self.manifest["cases"] if case["caseId"] == case_id)
        item = {
            "caseId": case_id,
            "resumeId": case["resumeId"],
            "roleId": case["roleId"],
            "roleCode": case["roleCode"],
            "roleName": case["roleName"],
            "roleVersion": case["roleVersion"],
            "applicationState": label,
            "label": label,
        }
        item.update(overrides)
        return item

    def formal_manifest(self):
        roles = [
            {"roleId": role_id, "roleCode": f"ROLE_{role_id}",
             "roleName": f"岗位 {role_id}", "roleVersion": "v1"}
            for role_id in (2, 3, 4, 5, 7, 9, 12)
        ]
        resumes = [{"resumeId": f"r{index:02d}"} for index in range(1, 31)]
        cases = []
        for resume in resumes:
            for role in roles:
                cases.append({
                    "caseId": f"c{len(cases) + 1:03d}",
                    "resumeId": resume["resumeId"], **role,
                })
        manifest = {
            "datasetId": "formal-dataset", "status": "FROZEN_FOR_PREDICTION",
            "containsLabels": False, "artifacts": {"jarSha256": "a" * 64},
            "resumes": resumes, "roles": roles, "cases": cases,
        }
        manifest["datasetSha256"] = self.canonical_sha256(manifest)
        return manifest

    def test_reports_complete_metrics_without_dropping_errors(self):
        predictions = self.predictions([
            self.item("c1", "需先补关键能力"), self.item("c2", "接近可申请"),
            self.item("c3", "接近可申请"),
        ])

        report = self.module.evaluate(self.manifest, self.gold, predictions,
                                      minimum_accuracy=0.90,
                                      minimum_macro_f1=0.0,
                                      minimum_class_recall=0.0)

        self.assertEqual(3, report["caseCount"])
        self.assertEqual(2, report["correct"])
        self.assertAlmostEqual(2 / 3, report["accuracy"])
        self.assertFalse(report["passed"])
        self.assertEqual(1.0, report["classMetrics"]["需先补关键能力"]["recall"])
        self.assertEqual(1.0, report["classMetrics"]["接近可申请"]["recall"])
        self.assertEqual(0.5, report["perRole"]["2"]["accuracy"])

    def test_rejects_missing_prediction_instead_of_reducing_denominator(self):
        predictions = self.predictions([
            self.item("c1", "需先补关键能力"), self.item("c2", "接近可申请"),
        ])

        with self.assertRaisesRegex(self.module.EvaluationError, "caseId"):
            self.module.evaluate(self.manifest, self.gold, predictions)

    def test_rejects_manifest_dataset_hash_mismatch(self):
        self.manifest["datasetId"] = "tampered"

        with self.assertRaisesRegex(self.module.EvaluationError, "datasetSha256"):
            self.module.evaluate(self.manifest, self.gold, self.predictions([
                self.item("c1", "需先补关键能力"), self.item("c2", "接近可申请"),
                self.item("c3", "接近可申请"),
            ]))

    def test_rejects_gold_from_another_dataset(self):
        self.gold["datasetSha256"] = "b" * 64

        with self.assertRaisesRegex(self.module.EvaluationError, "gold.*dataset"):
            self.module.evaluate(self.manifest, self.gold, self.predictions([
                self.item("c1", "需先补关键能力"), self.item("c2", "接近可申请"),
                self.item("c3", "接近可申请"),
            ]))

    def test_rejects_predictions_from_another_dataset(self):
        predictions = self.predictions([
            self.item("c1", "需先补关键能力"), self.item("c2", "接近可申请"),
            self.item("c3", "接近可申请"),
        ], datasetSha256="b" * 64)

        with self.assertRaisesRegex(self.module.EvaluationError, "predictions.*dataset"):
            self.module.evaluate(self.manifest, self.gold, predictions)

    def test_rejects_predictions_with_different_artifacts(self):
        predictions = self.predictions([
            self.item("c1", "需先补关键能力"), self.item("c2", "接近可申请"),
            self.item("c3", "接近可申请"),
        ], artifactHashes={"jarSha256": "b" * 64})

        with self.assertRaisesRegex(self.module.EvaluationError, "artifact"):
            self.module.evaluate(self.manifest, self.gold, predictions)

    def test_run_rejects_prediction_bound_to_different_manifest_file(self):
        predictions = self.predictions([
            self.item("c1", "需先补关键能力"), self.item("c2", "接近可申请"),
            self.item("c3", "接近可申请"),
        ], manifestFileSha256="b" * 64)
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            manifest_path = root / "manifest.json"
            gold_path = root / "gold.json"
            predictions_path = root / "predictions.json"
            manifest_path.write_text(json.dumps(self.manifest, ensure_ascii=False), encoding="utf-8")
            gold_path.write_text(json.dumps(self.gold, ensure_ascii=False), encoding="utf-8")
            predictions_path.write_text(json.dumps(predictions, ensure_ascii=False), encoding="utf-8")
            args = SimpleNamespace(
                manifest=manifest_path, gold=gold_path, predictions=predictions_path,
                report=root / "report.json", minimum_accuracy=0.90,
                minimum_macro_f1=0.85, minimum_class_recall=0.80)

            with self.assertRaisesRegex(self.module.EvaluationError, "manifestFileSha256"):
                self.module.run(args)

    def test_rejects_gold_identity_mismatch(self):
        self.gold["cases"][0]["roleId"] = 12
        predictions = self.predictions([
            self.item("c1", "需先补关键能力"), self.item("c2", "接近可申请"),
            self.item("c3", "可申请实习/校招"),
        ])

        with self.assertRaisesRegex(self.module.EvaluationError, "gold.*身份"):
            self.module.evaluate(self.manifest, self.gold, predictions)

    def test_rejects_prediction_identity_mismatch(self):
        predictions = self.predictions([
            self.item("c1", "需先补关键能力", resumeId="r2"),
            self.item("c2", "接近可申请"), self.item("c3", "可申请实习/校招"),
        ])

        with self.assertRaisesRegex(self.module.EvaluationError, "predictions.*身份"):
            self.module.evaluate(self.manifest, self.gold, predictions)

    def test_rejects_manifest_not_frozen_or_with_labels(self):
        predictions = self.predictions([
            self.item("c1", "需先补关键能力"), self.item("c2", "接近可申请"),
            self.item("c3", "可申请实习/校招"),
        ])
        self.manifest["containsLabels"] = True
        self.manifest["datasetSha256"] = self.canonical_sha256(self.manifest)

        with self.assertRaisesRegex(self.module.EvaluationError, "containsLabels"):
            self.module.evaluate(self.manifest, self.gold, predictions)

    def test_rejects_manifest_not_frozen(self):
        predictions = self.predictions([
            self.item("c1", "需先补关键能力"), self.item("c2", "接近可申请"),
            self.item("c3", "可申请实习/校招"),
        ])
        self.manifest["status"] = "INTAKE_INCOMPLETE"

        with self.assertRaisesRegex(self.module.EvaluationError, "FROZEN_FOR_PREDICTION"):
            self.module.evaluate(self.manifest, self.gold, predictions)

    def test_rejects_incomplete_formal_matrix(self):
        manifest = self.formal_manifest()
        manifest["cases"].pop()
        manifest["datasetSha256"] = self.canonical_sha256(manifest)

        with self.assertRaisesRegex(self.module.EvaluationError, "30.*7.*210"):
            self.module._validate_official_structure(manifest)

    def test_rejects_nonstandard_formal_role(self):
        manifest = self.formal_manifest()
        manifest["roles"][-1]["roleId"] = 13
        manifest["datasetSha256"] = self.canonical_sha256(manifest)

        with self.assertRaisesRegex(self.module.EvaluationError, "岗位"):
            self.module._validate_official_structure(manifest)

    def test_rejects_cli_threshold_below_floor(self):
        args = SimpleNamespace(minimum_accuracy=0.89, minimum_macro_f1=0.85,
                               minimum_class_recall=0.80)

        with self.assertRaisesRegex(self.module.EvaluationError, "阈值"):
            self.module._validate_thresholds(args)


if __name__ == "__main__":
    unittest.main()
