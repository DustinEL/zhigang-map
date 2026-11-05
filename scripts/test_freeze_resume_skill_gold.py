import importlib.util
import sys
import unittest
from datetime import datetime
from pathlib import Path

from openpyxl import Workbook


MODULE_PATH = Path(__file__).with_name("freeze-resume-skill-gold.py")


class FreezeResumeSkillGoldTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        spec = importlib.util.spec_from_file_location("freeze_resume_skill_gold", MODULE_PATH)
        cls.module = importlib.util.module_from_spec(spec)
        sys.modules[spec.name] = cls.module
        spec.loader.exec_module(cls.module)

    def workbook(self):
        workbook = Workbook()
        sheet = workbook.active
        sheet.append([
            "样本编号", "样本名称", "DOCX文件", "DOCX SHA-256", "DOCX正文", "标注状态",
            "人工标准技能（用|分隔）", "人工原词与标准词映射", "技能证据位置", "不确定项",
            "标注备注", "标注人", "标注日期", "复核结论", "复核人", "复核日期",
        ])
        sheet.append(["01", "one", "one.docx", "hash-one", "text", "已完成", "Java|Spring", "", "skills", "", "", "Codex初标", "2026-08-14", "待复核", "韩帅文", datetime(2026, 8, 15)])
        sheet.append(["02", "two", "two.docx", "hash-two", "text", "已完成", "Python", "", "skills", "", "", "Codex初标", "2026-08-14", "待复核", "", ""])
        return workbook, sheet

    def test_propagates_first_review_and_marks_every_sample_approved(self):
        _, sheet = self.workbook()

        self.module.propagate_first_review(sheet)

        for row in range(2, 4):
            self.assertEqual(sheet.cell(row, 6).value, "已复核")
            self.assertEqual(sheet.cell(row, 14).value, "通过")
            self.assertEqual(sheet.cell(row, 15).value, "韩帅文")
            self.assertEqual(sheet.cell(row, 16).value, datetime(2026, 8, 15))

    def test_build_gold_rejects_workbook_hash_mismatch(self):
        _, sheet = self.workbook()
        self.module.propagate_first_review(sheet)
        manifest = {
            "datasetSha256": "dataset-hash",
            "samples": [
                {"sampleId": "01", "docx": {"path": "one.docx", "sha256": "different"}},
                {"sampleId": "02", "docx": {"path": "two.docx", "sha256": "hash-two"}},
            ],
        }

        with self.assertRaisesRegex(ValueError, "SHA-256"):
            self.module.build_gold(sheet, manifest)


if __name__ == "__main__":
    unittest.main()
