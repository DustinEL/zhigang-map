import importlib.util
import json
import tempfile
import unittest
from pathlib import Path

from openpyxl import load_workbook


SCRIPT = Path(__file__).with_name("generate-jd-annotation-package.py")
SPEC = importlib.util.spec_from_file_location("generate_jd_annotation_package", SCRIPT)
MODULE = importlib.util.module_from_spec(SPEC)


class AnnotationPackageTest(unittest.TestCase):
    def setUp(self):
        if SCRIPT.exists():
            SPEC.loader.exec_module(MODULE)

    def test_generates_isolated_annotation_and_signoff_workbooks(self):
        records = [
            {
                "recordId": "HJD-TEST-0001",
                "candidateRecordId": "candidate-1",
                "companyName": "示例公司一",
                "jobTitle": "AI Agent工程师",
                "city": "北京",
                "level": "中级",
                "primaryTargetRoleCode": "AI_AGENT_ENGINEER",
                "secondaryTargetRoleCodes": ["RAG_APP_ENGINEER"],
                "sourceUrl": "https://example.com/jobs/1",
                "contentSha256": "1" * 64,
                "rawText": "AI Agent工程师\n岗位职责与任职要求" * 20,
            },
            {
                "recordId": "HJD-TEST-0002",
                "candidateRecordId": "candidate-2",
                "companyName": "示例公司二",
                "jobTitle": "Java工程师",
                "city": "上海",
                "level": "高级或管理岗",
                "primaryTargetRoleCode": "JAVA_ENGINEER",
                "secondaryTargetRoleCodes": [],
                "sourceUrl": "https://example.com/jobs/2",
                "contentSha256": "2" * 64,
                "rawText": "Java工程师\n岗位职责与任职要求" * 20,
            },
        ]
        manifest = {
            "recordCount": 2,
            "records": [
                {"recordId": record["recordId"], "contentSha256": record["contentSha256"]}
                for record in records
            ],
        }

        with tempfile.TemporaryDirectory() as temporary_directory:
            root = Path(temporary_directory)
            data_path = root / "official.jsonl"
            manifest_path = root / "manifest.json"
            output_dir = root / "annotation-package"
            data_path.write_text(
                "".join(json.dumps(record, ensure_ascii=False) + "\n" for record in records),
                encoding="utf-8",
            )
            manifest_path.write_text(json.dumps(manifest, ensure_ascii=False), encoding="utf-8")

            summary = MODULE.generate_package(data_path, manifest_path, output_dir)

            expected_files = {
                "custodian-signoff.xlsx",
                "annotator-a.xlsx",
                "annotator-b.xlsx",
                "adjudication.xlsx",
                "annotation-evidence.xlsx",
                "标注与签字操作说明.md",
                "package-checksums.sha256",
            }
            self.assertEqual(expected_files, {path.name for path in output_dir.iterdir()})
            self.assertEqual(2, summary["recordCount"])

            annotation_headers = None
            for filename in ("annotator-a.xlsx", "annotator-b.xlsx"):
                workbook = load_workbook(output_dir / filename)
                self.assertEqual(["说明与签字", "JD标注"], workbook.sheetnames)
                worksheet = workbook["JD标注"]
                headers = [cell.value for cell in worksheet[1]]
                annotation_headers = annotation_headers or headers
                self.assertEqual(annotation_headers, headers)
                self.assertNotIn("预测", "".join(headers))
                self.assertNotIn("prediction", "".join(headers).lower())
                self.assertEqual(3, worksheet.max_row)
                self.assertEqual("A2", worksheet.freeze_panes)
                self.assertTrue(worksheet.auto_filter.ref)
                self.assertGreater(len(worksheet.data_validations.dataValidation), 0)
                record_ids = [worksheet.cell(row=row, column=1).value for row in range(2, 4)]
                self.assertEqual(["HJD-TEST-0001", "HJD-TEST-0002"], record_ids)
                raw_text_column = headers.index("JD原文") + 1
                status_column = headers.index("标注状态") + 1
                self.assertTrue(worksheet.cell(row=2, column=raw_text_column).value)
                self.assertIsNone(worksheet.cell(row=2, column=status_column).value)

            custodian = load_workbook(output_dir / "custodian-signoff.xlsx")
            self.assertIn("签字与确认", custodian.sheetnames)
            adjudication = load_workbook(output_dir / "adjudication.xlsx")
            self.assertIn("裁决", adjudication.sheetnames)
            self.assertEqual(3, adjudication["裁决"].max_row)
            evidence = load_workbook(output_dir / "annotation-evidence.xlsx")
            roles = [evidence["人员与签字"].cell(row=row, column=1).value for row in range(2, 6)]
            self.assertEqual(["数据保管员", "标注员 A", "标注员 B", "裁决员 C"], roles)

            checksum_lines = (output_dir / "package-checksums.sha256").read_text(encoding="utf-8").splitlines()
            self.assertEqual(6, len(checksum_lines))

    def test_rejects_manifest_record_mismatch(self):
        with tempfile.TemporaryDirectory() as temporary_directory:
            root = Path(temporary_directory)
            data_path = root / "official.jsonl"
            manifest_path = root / "manifest.json"
            data_path.write_text(json.dumps({"recordId": "HJD-1"}) + "\n", encoding="utf-8")
            manifest_path.write_text(json.dumps({"recordCount": 0, "records": []}), encoding="utf-8")

            with self.assertRaisesRegex(ValueError, "manifest"):
                MODULE.generate_package(data_path, manifest_path, root / "output")


if __name__ == "__main__":
    unittest.main()
