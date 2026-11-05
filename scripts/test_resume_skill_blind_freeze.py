import tempfile
import unittest
from datetime import date
from pathlib import Path

from openpyxl import Workbook

from resume_skill_blind_freeze import build_gold, export_initial_annotations, freeze_gold, validate_double_annotation


SUMMARY_HEADERS = [
    "样本编号", "样本名称", "DOCX文件", "DOCX SHA-256", "标注状态", "标注人", "标注日期",
    "复核结论", "复核人", "复核日期", "复核理由",
]
EVIDENCE_HEADERS = [
    "样本编号", "标准技能", "原词", "章节", "定位", "原文引用", "命名来源",
    "初标决定", "不确定原因", "标注备注", "复核决定", "复核理由",
]


class ResumeSkillBlindFreezeTest(unittest.TestCase):
    def workbook(self, reviewed=True, quote="明确使用 Java"):
        workbook = Workbook()
        summary = workbook.active
        summary.title = "样本汇总"
        summary.append(SUMMARY_HEADERS)
        summary.append([
            "02", "02_Person", "resumes/02_Person.docx", "docx-hash", "已复核" if reviewed else "已完成",
            "Codex初标", date(2026, 8, 16), "通过" if reviewed else "待复核", "韩帅文" if reviewed else "",
            date(2026, 8, 16) if reviewed else "", "逐项核对通过" if reviewed else "",
        ])
        evidence = workbook.create_sheet("技能证据")
        evidence.append(EVIDENCE_HEADERS)
        evidence.append([
            "02", "Java", "Java 17", "专业技能", "段落 3", quote, "V1", "INCLUDE", "", "",
            "ACCEPT" if reviewed else "", "证据充分" if reviewed else "",
        ])
        workbook.create_sheet("独立双标").append(["样本编号", "用户独立技能（用|分隔）", "标注人", "标注日期", "备注"])
        return workbook

    def manifest(self):
        return {
            "datasetSha256": "dataset-hash",
            "sampleCount": 1,
            "samples": [{
                "sampleId": "02", "sampleName": "02_Person",
                "docx": {"path": "resumes/02_Person.docx", "sha256": "docx-hash"},
                "pdf": {"path": "resumes/02_Person.pdf", "sha256": "pdf-hash"},
                "sourceZip": {"path": "resumes/source.zip", "sha256": "zip-hash"},
            }],
        }

    def test_builds_gold_only_from_explicitly_reviewed_evidence(self):
        gold = build_gold(self.workbook(), self.manifest(), "baseline-hash")

        self.assertEqual(gold["goldStatus"], "FROZEN")
        self.assertEqual(gold["samples"][0]["skills"], ["Java"])
        self.assertEqual(gold["samples"][0]["reviewer"], "韩帅文")
        self.assertEqual(gold["samples"][0]["evidence"][0]["surfaceForm"], "Java 17")

    def test_rejects_unreviewed_sample_instead_of_propagating_approval(self):
        with self.assertRaisesRegex(ValueError, "02.*复核"):
            build_gold(self.workbook(reviewed=False), self.manifest(), "baseline-hash")

    def test_rejects_skill_without_exact_quote(self):
        with self.assertRaisesRegex(ValueError, "证据"):
            build_gold(self.workbook(quote=""), self.manifest(), "baseline-hash")

    def test_refuses_to_overwrite_frozen_gold(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "gold.json"
            freeze_gold(self.workbook(), self.manifest(), "baseline-hash", output)

            with self.assertRaisesRegex(FileExistsError, "overwrite"):
                freeze_gold(self.workbook(), self.manifest(), "baseline-hash", output)

    def test_double_annotation_requires_micro_f1_and_per_sample_jaccard(self):
        workbook = Workbook()
        evidence = workbook.active
        evidence.title = "技能证据"
        evidence.append(EVIDENCE_HEADERS)
        evidence.append(["01", "Java", "Java", "技能", "p1", "Java", "V1", "INCLUDE", "", "", "ACCEPT", "ok"])
        double = workbook.create_sheet("独立双标")
        double.append(["样本编号", "用户独立技能（用|分隔）", "标注人", "标注日期", "备注", "裁决状态", "裁决说明"])
        double.append(["01", "Python", "Reviewer", date(2026, 8, 16), "", "RESOLVED", "按原文裁决"])

        with self.assertRaisesRegex(ValueError, "双标"):
            validate_double_annotation(workbook, {"samples": [{"sampleId": "01"}]})

    def test_initial_annotation_export_excludes_review_and_prediction_data(self):
        workbook = self.workbook(reviewed=False)

        exported = export_initial_annotations(workbook, self.manifest())

        self.assertEqual(exported["annotations"]["02"]["skills"], ["Java"])
        self.assertNotIn("reviewer", exported["annotations"]["02"])
        self.assertNotIn("predictedSkills", exported["annotations"]["02"])


if __name__ == "__main__":
    unittest.main()
