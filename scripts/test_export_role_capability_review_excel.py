import csv
import importlib.util
import json
import tempfile
import unittest
from pathlib import Path

from openpyxl import load_workbook


CREATE_SCRIPT = Path(__file__).with_name("create-role-capability-review-excel.py")
EXPORT_SCRIPT = Path(__file__).with_name("export-role-capability-review-excel.py")


def load(path, name):
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class ExportRoleCapabilityReviewExcelTest(unittest.TestCase):
    def setUp(self):
        self.creator = load(CREATE_SCRIPT, "create_role_capability_review_excel")
        self.exporter = load(EXPORT_SCRIPT, "export_role_capability_review_excel")
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.cards = self.root / "cards.json"
        self.output = self.root / "output"
        self.cards.write_text(json.dumps({
            "schemaVersion": "v2",
            "cards": [{
                "roleId": 2, "roleCode": "JAVA", "roleName": "Java 开发工程师", "version": "v2",
                "coreCapabilities": [{"id": "language", "alternatives": ["Java"],
                                      "nearEvidenceLevel": 2, "readyEvidenceLevel": 2, "blocking": True}],
                "transferableSkills": [], "bonusSkills": [], "sourceReferences": ["source"],
            }],
        }, ensure_ascii=False), encoding="utf-8")
        self.workbook = self.creator.create_workbooks(self.cards, self.output)[0]

    def tearDown(self):
        self.temp.cleanup()

    def test_exports_chinese_choices_to_validator_csv(self):
        book = load_workbook(self.workbook)
        sheet = book["审核表"]
        sheet["B2"] = "审核员甲"
        sheet["E2"] = "2026-08-14"
        for row in range(7, sheet.max_row + 1):
            sheet.cell(row, 9, "通过")
            sheet.cell(row, 14, "已核对岗位职责")
        book.save(self.workbook)
        destination = self.output / "reviewer-a.csv"

        rows = self.exporter.export_review(self.workbook, destination)

        self.assertEqual(3, len(rows))
        self.assertTrue(all(row["decision"] == "ACCEPT" for row in rows))
        self.assertTrue(all(row["reviewerId"] == "审核员甲" for row in rows))
        with destination.open(encoding="utf-8-sig", newline="") as handle:
            self.assertEqual(rows, list(csv.DictReader(handle)))

    def test_rejects_incomplete_workbook(self):
        with self.assertRaisesRegex(self.exporter.ValidationError, "未完成"):
            self.exporter.export_review(self.workbook, self.output / "reviewer-a.csv")


if __name__ == "__main__":
    unittest.main()
