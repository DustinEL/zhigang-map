import hashlib
import json
import os
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


SCRIPT = Path(__file__).with_name("freeze-match-blind-gold.py")
VALID_LABELS = (
    "可申请实习/校招",
    "接近可申请",
    "需先补关键能力",
    "无法判断",
)


class FreezeMatchBlindGoldTest(unittest.TestCase):
    def setUp(self):
        self.temp_dir = tempfile.TemporaryDirectory()
        self.root = Path(self.temp_dir.name)
        self.manifest_path = self.root / "manifest.json"
        self.annotation_path = self.root / "annotation.md"
        self.gold_path = self.root / "gold.json"
        self.manifest = self.build_manifest()
        self.write_manifest()

    def tearDown(self):
        self.temp_dir.cleanup()

    @staticmethod
    def build_manifest():
        resumes = [
            {"resumeId": f"{number:02d}", "resumeName": f"resume-{number:02d}"}
            for number in range(8, 20)
        ]
        roles = [
            {"roleId": role_id, "roleName": role_name}
            for role_id, role_name in (
                (2, "Java 开发工程师"),
                (4, "AI 应用后端工程师"),
                (3, "AI Agent 开发工程师"),
                (5, "LLMOps / 模型服务工程师"),
                (7, "大模型应用开发工程师"),
                (9, "AI 数据工程师"),
                (12, "AI 安全测试工程师"),
                (13, "大模型推理加速工程师"),
            )
        ]
        cases = []
        case_number = 1
        for resume in resumes:
            for role in roles:
                cases.append(
                    {
                        "caseId": f"MB-{case_number:03d}",
                        "resumeId": resume["resumeId"],
                        "roleId": role["roleId"],
                    }
                )
                case_number += 1
        manifest = {
            "schemaVersion": 1,
            "datasetId": "match-blind-96-v1",
            "resumes": resumes,
            "roles": roles,
            "cases": cases,
        }
        canonical = json.dumps(
            manifest, ensure_ascii=False, sort_keys=True, separators=(",", ":")
        ).encode("utf-8")
        manifest["datasetSha256"] = hashlib.sha256(canonical).hexdigest()
        return manifest

    def write_manifest(self):
        unhashed = dict(self.manifest)
        unhashed.pop("datasetSha256", None)
        canonical = json.dumps(
            unhashed, ensure_ascii=False, sort_keys=True, separators=(",", ":")
        ).encode("utf-8")
        self.manifest["datasetSha256"] = hashlib.sha256(canonical).hexdigest()
        self.manifest_path.write_text(
            json.dumps(self.manifest, ensure_ascii=False, indent=2) + "\n",
            encoding="utf-8",
        )

    def write_annotation(self, labels=None, mutate=None):
        labels = labels or [VALID_LABELS[index % 3] for index in range(96)]
        resume_names = {
            resume["resumeId"]: resume["resumeName"] for resume in self.manifest["resumes"]
        }
        role_names = {
            role["roleId"]: role["roleName"] for role in self.manifest["roles"]
        }
        rows = []
        for index, case in enumerate(self.manifest["cases"]):
            row = [
                case["caseId"],
                case["resumeId"],
                resume_names[case["resumeId"]],
                str(case["roleId"]),
                role_names[case["roleId"]],
                labels[index],
                f"evidence-{index + 1}",
                "",
            ]
            rows.append(row)
        if mutate:
            mutate(rows)
        lines = [
            "# annotation",
            "",
            "| Case ID | 简历编号 | 简历 | 岗位编号 | 岗位 | 人工标签 | 判断依据 | 备注 |",
            "|---|---:|---|---:|---|---|---|---|",
        ]
        lines.extend("| " + " | ".join(row) + " |" for row in rows)
        self.annotation_path.write_text("\n".join(lines) + "\n", encoding="utf-8")

    def run_cli(self, *extra_args):
        environment = os.environ.copy()
        environment["PYTHONUTF8"] = "1"
        return subprocess.run(
            [
                sys.executable,
                str(SCRIPT),
                "--manifest",
                str(self.manifest_path),
                "--annotation",
                str(self.annotation_path),
                "--gold",
                str(self.gold_path),
                *extra_args,
            ],
            capture_output=True,
            text=True,
            encoding="utf-8",
            env=environment,
            check=False,
        )

    def test_validation_only_reports_unlabeled_count_without_writing_gold(self):
        labels = [VALID_LABELS[0]] * 96
        labels[3] = ""
        labels[57] = ""
        self.write_annotation(labels)

        result = self.run_cli("--reviewer", "reviewer-a", "--validate-only")

        self.assertEqual(2, result.returncode)
        self.assertIn("未标注: 2", result.stdout)
        self.assertEqual("未标注: 2", result.stdout.strip())
        self.assertFalse(self.gold_path.exists())

    def test_validation_only_rejects_blank_reviewer(self):
        self.write_annotation()

        result = self.run_cli("--reviewer", "   ", "--validate-only")

        self.assertNotEqual(0, result.returncode)
        self.assertIn("审核人", result.stderr)
        self.assertFalse(self.gold_path.exists())

    def test_freeze_rejects_incomplete_labels(self):
        labels = [VALID_LABELS[0]] * 96
        labels[10] = ""
        self.write_annotation(labels)

        result = self.run_cli("--reviewer", "reviewer-a")

        self.assertNotEqual(0, result.returncode)
        self.assertIn("未完成", result.stderr)
        self.assertFalse(self.gold_path.exists())

    def test_freeze_rejects_invalid_label(self):
        labels = [VALID_LABELS[0]] * 96
        labels[5] = "推荐"
        self.write_annotation(labels)

        result = self.run_cli("--reviewer", "reviewer-a")

        self.assertNotEqual(0, result.returncode)
        self.assertIn("非法标签", result.stderr)

    def test_freeze_rejects_duplicate_case_id(self):
        self.write_annotation(mutate=lambda rows: rows[1].__setitem__(0, rows[0][0]))

        result = self.run_cli("--reviewer", "reviewer-a")

        self.assertNotEqual(0, result.returncode)
        self.assertIn("重复", result.stderr)

    def test_freeze_rejects_annotation_manifest_mismatch(self):
        self.write_annotation(mutate=lambda rows: rows[0].__setitem__(3, "999"))

        result = self.run_cli("--reviewer", "reviewer-a")

        self.assertNotEqual(0, result.returncode)
        self.assertIn("清单不一致", result.stderr)

    def test_freeze_rejects_blank_reviewer(self):
        self.write_annotation()

        result = self.run_cli("--reviewer", "   ")

        self.assertNotEqual(0, result.returncode)
        self.assertIn("审核人", result.stderr)

    def test_freeze_rejects_manifest_not_containing_exactly_96_cases(self):
        self.manifest["cases"].pop()
        self.write_manifest()
        self.write_annotation()

        result = self.run_cli("--reviewer", "reviewer-a")

        self.assertNotEqual(0, result.returncode)
        self.assertIn("96", result.stderr)

    def test_freeze_writes_hashes_valid_and_undecidable_cases(self):
        labels = [VALID_LABELS[index % 3] for index in range(96)]
        labels[4] = VALID_LABELS[3]
        labels[80] = VALID_LABELS[3]
        self.write_annotation(labels)

        result = self.run_cli("--reviewer", "reviewer-a")

        self.assertEqual(0, result.returncode, result.stderr)
        gold = json.loads(self.gold_path.read_text(encoding="utf-8"))
        self.assertEqual("reviewer-a", gold["reviewer"])
        self.assertEqual(94, len(gold["validCases"]))
        self.assertEqual(2, len(gold["undecidableCases"]))
        self.assertRegex(gold["frozenAt"], r"^\d{4}-\d{2}-\d{2}T")
        self.assertEqual(self.file_sha256(self.manifest_path), gold["manifestSha256"])
        self.assertEqual(self.file_sha256(self.annotation_path), gold["annotationSha256"])
        payload = dict(gold)
        gold_sha256 = payload.pop("goldSha256")
        canonical = json.dumps(
            payload, ensure_ascii=False, sort_keys=True, separators=(",", ":")
        ).encode("utf-8")
        self.assertEqual(hashlib.sha256(canonical).hexdigest(), gold_sha256)

    @staticmethod
    def file_sha256(path):
        return hashlib.sha256(path.read_bytes()).hexdigest()


if __name__ == "__main__":
    unittest.main()
