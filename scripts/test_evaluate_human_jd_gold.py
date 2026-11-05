import importlib.util
import json
import sys
import unittest
import urllib.request
import tempfile
from pathlib import Path


MODULE_PATH = Path(__file__).with_name("evaluate-human-jd-gold.py")


class HumanGoldMetricTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        spec = importlib.util.spec_from_file_location("human_gold_eval", MODULE_PATH)
        cls.module = importlib.util.module_from_spec(spec)
        sys.modules[spec.name] = cls.module
        spec.loader.exec_module(cls.module)

    def test_responsibility_bigram_similarity_matches_old_metric(self):
        score = self.module.responsibility_similarity(
            "负责数据平台架构设计与技术选型",
            "负责数据平台架构设计与技术选型",
        )
        self.assertGreaterEqual(score, 0.62)

    def test_responsibility_matching_is_one_to_one(self):
        result = self.module.match_responsibilities(
            ["负责平台建设", "负责系统维护"],
            ["负责平台建设"],
        )
        self.assertEqual((result.tp, result.fp, result.fn), (1, 0, 1))

    def test_micro_metrics_count_entities(self):
        metric = self.module.metric_from_counts(9, 1, 1)
        self.assertEqual(metric["precision"], 90.0)
        self.assertEqual(metric["recall"], 90.0)
        self.assertEqual(metric["f1"], 90.0)

    def test_cluster_bootstrap_uses_aggregated_micro_counts(self):
        case_counts = [
            self.module.Counts(9, 1, 0),
            self.module.Counts(0, 0, 9),
        ]

        interval = self.module.bootstrap_micro_metric(case_counts, samples=200, seed=7)

        self.assertEqual(interval["pointEstimate"], 64.29)
        self.assertEqual(interval["bootstrapSamples"], 200)
        self.assertEqual(interval["seed"], 7)
        self.assertLessEqual(interval["lower"], interval["pointEstimate"])
        self.assertGreaterEqual(interval["upper"], interval["pointEstimate"])

    def test_acceptance_gate_rejects_combined_f1_when_skill_recall_is_low(self):
        result = {
            "primarySkillsResponsibilities": {"f1": 92.5},
            "fields": {
                "skill": {"precision": 94.0, "recall": 89.9, "f1": 91.9},
                "responsibility": {"f1": 94.0},
            },
            "microConfidenceIntervals95": {
                "primarySkillsResponsibilities": {"lower": 90.5},
                "skill": {"lower": 90.1},
                "responsibility": {"lower": 91.0},
            },
        }

        acceptance = self.module.evaluate_acceptance(result)

        self.assertFalse(acceptance["passed"])
        self.assertIn("skillRecall", acceptance["failedGates"])

    def test_acceptance_gate_requires_all_point_and_confidence_gates(self):
        result = {
            "primarySkillsResponsibilities": {"f1": 92.5},
            "fields": {
                "skill": {"precision": 93.0, "recall": 91.0, "f1": 92.0},
                "responsibility": {"f1": 93.0},
            },
            "microConfidenceIntervals95": {
                "primarySkillsResponsibilities": {"lower": 90.2},
                "skill": {"lower": 90.1},
                "responsibility": {"lower": 90.4},
            },
        }

        acceptance = self.module.evaluate_acceptance(result)

        self.assertTrue(acceptance["passed"])
        self.assertEqual(acceptance["failedGates"], [])

    def test_official_mode_requires_fresh_predictions_and_reviewed_gold(self):
        with self.assertRaisesRegex(ValueError, "--fresh"):
            self.module.validate_run_mode(True, False, True, "runtime", "artifact")

        with self.assertRaisesRegex(ValueError, "--require-reviewed"):
            self.module.validate_run_mode(True, True, False, "runtime", "artifact")

        with self.assertRaisesRegex(ValueError, "--runtime-fingerprint"):
            self.module.validate_run_mode(True, True, True, "", "artifact")

        with self.assertRaisesRegex(ValueError, "--artifact-sha256"):
            self.module.validate_run_mode(True, True, True, "runtime", "")

        self.module.validate_run_mode(True, True, True, "runtime", "artifact")

    def test_official_evidence_requires_200_independent_double_annotated_samples(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            manifest = root / "manifest.json"
            leakage = root / "leakage.json"
            annotation = root / "annotation.json"
            manifest.write_text(json.dumps({"schemaVersion": "1.0.0", "sampleCount": 200}), encoding="utf-8")
            leakage.write_text(json.dumps({"passed": True, "candidateCount": 200, "findings": []}), encoding="utf-8")
            annotation.write_text(json.dumps({
                "sampleCount": 200,
                "annotators": ["annotator-a", "annotator-b"],
                "adjudicator": "adjudicator-c",
                "doubleAnnotated": True,
                "allDisagreementsAdjudicated": True,
                "predictionFrozenBeforeGold": True,
            }), encoding="utf-8")

            evidence = self.module.validate_official_evidence(
                True, 200, manifest, leakage, annotation)

            self.assertEqual(evidence["sampleCount"], 200)
            with self.assertRaisesRegex(ValueError, "至少 200"):
                self.module.validate_official_evidence(True, 199, manifest, leakage, annotation)

    def test_official_evidence_rejects_failed_leakage_or_same_reviewer(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            manifest = root / "manifest.json"
            leakage = root / "leakage.json"
            annotation = root / "annotation.json"
            manifest.write_text(json.dumps({"schemaVersion": "1.0.0", "sampleCount": 200}), encoding="utf-8")
            leakage.write_text(json.dumps({"passed": False, "candidateCount": 200, "findings": [{}]}), encoding="utf-8")
            annotation.write_text(json.dumps({
                "sampleCount": 200, "annotators": ["same", "same"], "adjudicator": "same",
                "doubleAnnotated": True, "allDisagreementsAdjudicated": True,
                "predictionFrozenBeforeGold": True,
            }), encoding="utf-8")

            with self.assertRaisesRegex(ValueError, "泄漏审计"):
                self.module.validate_official_evidence(True, 200, manifest, leakage, annotation)

    def test_non_official_report_cannot_claim_formal_pass(self):
        status = self.module.evaluation_status(official=False, metrics_passed=True)

        self.assertEqual(status["gateStatus"], "PASS_METRICS_NON_OFFICIAL")
        self.assertFalse(status["passed"])

    def test_official_report_can_pass_only_when_metrics_pass(self):
        self.assertEqual(
            self.module.evaluation_status(official=True, metrics_passed=True),
            {"gateStatus": "PASS_CLAIM_90", "passed": True},
        )
        self.assertEqual(
            self.module.evaluation_status(official=True, metrics_passed=False),
            {"gateStatus": "FAIL_CLAIM_90", "passed": False},
        )

    def test_versioned_cache_rejects_different_dataset(self):
        with tempfile.TemporaryDirectory() as directory:
            cache = Path(directory) / "predictions.json"
            cache.write_text(
                '{"schemaVersion":"2.0.0","datasetSha256":"gold-a","predictions":{}}',
                encoding="utf-8",
            )

            with self.assertRaisesRegex(ValueError, "数据集哈希不一致"):
                self.module.load_prediction_cache(cache, "gold-b", allow_legacy=False)

    def test_prediction_cache_key_changes_with_input_text(self):
        first = {"sampleNumber": 1, "recordId": 101, "inputText": "岗位名称：A"}
        second = {"sampleNumber": 1, "recordId": 101, "inputText": "岗位名称：B"}

        self.assertNotEqual(
            self.module.prediction_cache_key(first),
            self.module.prediction_cache_key(second),
        )

    def test_official_cache_rejects_legacy_sample_number_map(self):
        with tempfile.TemporaryDirectory() as directory:
            cache = Path(directory) / "predictions.json"
            cache.write_text('{"1":{"title":"旧预测"}}', encoding="utf-8")

            with self.assertRaisesRegex(ValueError, "旧版预测缓存"):
                self.module.load_prediction_cache(cache, "gold", allow_legacy=False)

    def test_official_cache_rejects_different_runtime_or_artifact_binding(self):
        with tempfile.TemporaryDirectory() as directory:
            cache = Path(directory) / "predictions.json"
            cache.write_text(json.dumps({
                "schemaVersion": "2.0.0",
                "datasetSha256": "gold",
                "binding": {"runtimeFingerprint": "runtime-a", "artifactSha256": "artifact-a",
                            "metricPolicySha256": "metric-a"},
                "predictions": {},
            }), encoding="utf-8")

            with self.assertRaisesRegex(ValueError, "运行时或制品绑定不一致"):
                self.module.load_prediction_cache(
                    cache, "gold", allow_legacy=False,
                    expected_binding={"runtimeFingerprint": "runtime-b", "artifactSha256": "artifact-a",
                                      "metricPolicySha256": "metric-a"},
                )

    def test_skill_normalization_ignores_case_and_spacing(self):
        self.assertEqual(
            self.module.normalize_skill(" Spring Boot "),
            self.module.normalize_skill("spring   boot"),
        )

    def test_skill_normalization_uses_frozen_policy_aliases(self):
        equivalent_groups = [
            ("大语言模型", "大模型", "LLM"),
            ("AI Agent", "智能体", "Agent"),
            ("Prompt Engineering", "提示词工程", "Prompt工程"),
            ("推荐算法", "推荐系统"),
            ("Transformer", "Transformers"),
            ("多智能体", "Multi-Agent", "Multi Agent"),
            ("模型微调", "Fine-tuning", "Fine tuning"),
        ]

        for group in equivalent_groups:
            with self.subTest(group=group):
                normalized = {self.module.normalize_skill(value) for value in group}
                self.assertEqual(len(normalized), 1)

    def test_skill_normalization_keeps_near_neighbor_skills_distinct(self):
        distinct_pairs = [
            ("AI", "AI Agent"),
            ("Spring", "Spring Boot"),
            ("Java", "JavaScript"),
            ("SQL", "SQL Server"),
        ]

        for left, right in distinct_pairs:
            with self.subTest(left=left, right=right):
                self.assertNotEqual(
                    self.module.normalize_skill(left),
                    self.module.normalize_skill(right),
                )

    def test_permit_all_csrf_response_without_token_is_accepted(self):
        client = self.module.ApiClient("http://127.0.0.1:1", "", "")

        class Response:
            def __enter__(self):
                return self

            def __exit__(self, *args):
                return False

            def read(self):
                return b'{"code":"OK","message":"success"}'

        client.opener.open = lambda request, timeout: Response()

        client.load_csrf()

        self.assertEqual(client.csrf_token, "")
        self.assertNotIn("X-XSRF-TOKEN", client.headers(mutating=True))

    def test_fresh_evaluation_rejects_existing_cache(self):
        with tempfile.TemporaryDirectory() as directory:
            cache = Path(directory) / "predictions.json"
            cache.write_text("{}", encoding="utf-8")

            with self.assertRaisesRegex(FileExistsError, "全新缓存"):
                self.module.ensure_fresh_cache(cache, fresh=True)

    def test_load_gold_accepts_reviewed_blind_dataset_size(self):
        from openpyxl import Workbook

        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "blind.xlsx"
            workbook = Workbook()
            sheet = workbook.active
            sheet.title = "JD人工标注"
            sheet.append([f"column-{index}" for index in range(24)])
            row = [None] * 24
            row[0] = 1
            row[1] = 101
            row[6] = "后端工程师"
            row[8] = "岗位职责：负责系统开发。"
            row[9] = "已复核"
            row[10] = "后端工程师"
            row[14] = "Java|SQL"
            row[15] = "负责系统开发"
            row[20] = "通过"
            row[21] = "复核人"
            row[22] = "2026-08-14"
            row[23] = "hash"
            sheet.append(row)
            workbook.save(path)

            cases = self.module.load_gold(path, expected_count=1, require_reviewed=True)

            self.assertEqual(len(cases), 1)
            self.assertEqual(cases[0]["skills"], ["Java", "SQL"])


if __name__ == "__main__":
    unittest.main()
