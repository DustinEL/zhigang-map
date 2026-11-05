import importlib.util
import json
import tempfile
import unittest
from pathlib import Path

from openpyxl import load_workbook


SCRIPT = Path(__file__).with_name("create-role-capability-review-excel.py")


def load_module():
    spec = importlib.util.spec_from_file_location("create_role_capability_review_excel", SCRIPT)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class CreateRoleCapabilityReviewExcelTest(unittest.TestCase):
    def test_generates_two_blank_chinese_review_workbooks(self):
        module = load_module()
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            cards = root / "cards.json"
            output = root / "output"
            cards.write_text(json.dumps({
                "schemaVersion": "role-capability-v2.0",
                "cards": [{
                    "roleId": 2, "roleCode": "JAVA", "roleName": "Java 开发工程师", "version": "java-v2",
                    "coreCapabilities": [{"id": "language", "alternatives": ["Java"],
                                          "nearEvidenceLevel": 2, "readyEvidenceLevel": 2, "blocking": True}],
                    "transferableSkills": ["Python"], "bonusSkills": ["Kafka"],
                    "sourceReferences": ["source-1"],
                }],
            }, ensure_ascii=False), encoding="utf-8")

            paths = module.create_workbooks(cards, output)

            self.assertEqual(2, len(paths))
            for path in paths:
                workbook = load_workbook(path)
                self.assertEqual(["填写说明", "审核表", "_程序数据"], workbook.sheetnames)
                self.assertEqual("hidden", workbook["_程序数据"].sheet_state)
                sheet = workbook["审核表"]
                self.assertEqual("审核结论", sheet["I6"].value)
                self.assertEqual("A7", sheet.freeze_panes)
                self.assertEqual(3, sheet.max_row - 6)
                self.assertTrue(all(sheet.cell(row, 9).value is None for row in range(7, sheet.max_row + 1)))
                validations = list(sheet.data_validations.dataValidation)
                self.assertTrue(any("通过,修改,驳回" in validation.formula1 for validation in validations))
                self.assertIsNone(sheet["B2"].value)


if __name__ == "__main__":
    unittest.main()
