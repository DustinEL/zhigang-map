import hashlib
import importlib.util
import json
import tempfile
import unittest
from pathlib import Path

from openpyxl import load_workbook


SCRIPT = Path(__file__).with_name("split-jd-pilot-holdout.py")
SPEC = importlib.util.spec_from_file_location("split_jd_pilot_holdout", SCRIPT)
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class JdPilotHoldoutSplitTest(unittest.TestCase):
    ROLES = [f"ROLE_{index:02d}" for index in range(12)]

    def records(self):
        records = []
        for index in range(240):
            raw_text = f"岗位职责与任职要求 {index} " * 20
            records.append({
                "recordId": f"HJD-{index + 1:04d}",
                "candidateRecordId": f"candidate-{index + 1:04d}",
                "primaryTargetRoleCode": self.ROLES[index % len(self.ROLES)],
                "secondaryTargetRoleCodes": [],
                "sourcePlatform": f"source-{index % 7}",
                "sourceName": f"source-{index % 7}",
                "sourceType": "公开招聘平台 JD",
                "sourceUrl": f"https://example.test/jobs/{index}",
                "company": f"company-{index % 73}",
                "companyName": f"company-{index % 73}",
                "jobTitle": f"岗位-{index}",
                "city": f"city-{index % 19}",
                "level": "中级",
                "rawText": raw_text,
                "contentSha256": hashlib.sha256(raw_text.encode("utf-8")).hexdigest(),
            })
        return records

    def test_split_is_deterministic_stratified_and_exhaustive(self):
        records = self.records()

        first_pilot, first_holdout, first_summary = MODULE.split_records(records, 30, 20260818)
        second_pilot, second_holdout, second_summary = MODULE.split_records(records, 30, 20260818)

        first_ids = {record["recordId"] for record in first_pilot}
        holdout_ids = {record["recordId"] for record in first_holdout}
        self.assertEqual(first_ids, {record["recordId"] for record in second_pilot})
        self.assertEqual(first_summary, second_summary)
        self.assertEqual(30, len(first_pilot))
        self.assertEqual(210, len(first_holdout))
        self.assertFalse(first_ids & holdout_ids)
        self.assertEqual({record["recordId"] for record in records}, first_ids | holdout_ids)
        self.assertEqual(set(self.ROLES), set(first_summary["pilotByPrimaryTargetRole"]))
        self.assertTrue(all(count >= 1 for count in first_summary["pilotByPrimaryTargetRole"].values()))

    def test_write_outputs_binds_hashes_and_creates_blank_annotation_workbook(self):
        records = self.records()
        manifest = {
            "schemaVersion": "1.0.0",
            "recordCount": len(records),
            "records": [
                {"recordId": record["recordId"], "contentSha256": record["contentSha256"]}
                for record in records
            ],
        }
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            data_path = root / "official.jsonl"
            manifest_path = root / "manifest.json"
            output_dir = root / "pilot-split"
            data_path.write_text(
                "".join(json.dumps(record, ensure_ascii=False) + "\n" for record in records),
                encoding="utf-8",
            )
            manifest_path.write_text(json.dumps(manifest, ensure_ascii=False), encoding="utf-8")

            result = MODULE.write_outputs(data_path, manifest_path, output_dir, 30, 20260818)

            pilot_manifest = json.loads((output_dir / "pilot-30-manifest.json").read_text(encoding="utf-8"))
            holdout_manifest = json.loads((output_dir / "holdout-210-manifest.json").read_text(encoding="utf-8"))
            self.assertEqual((30, 30), (pilot_manifest["recordCount"], pilot_manifest["sampleCount"]))
            self.assertEqual((210, 210), (holdout_manifest["recordCount"], holdout_manifest["sampleCount"]))
            self.assertEqual(30, len(pilot_manifest["records"]))
            self.assertEqual(210, len(holdout_manifest["records"]))
            self.assertEqual(0, result["intersectionCount"])
            self.assertEqual(240, result["unionCount"])

            workbook = load_workbook(output_dir / "annotation" / "annotator.xlsx", data_only=True)
            worksheet = workbook["JD标注"]
            headers = [cell.value for cell in worksheet[1]]
            self.assertEqual(30, worksheet.max_row - 1)
            self.assertFalse(any("预测" in str(header) or "prediction" in str(header).lower() for header in headers))
            for header in ("标准岗位名称", "技能标注", "核心职责标注", "标注状态"):
                column = headers.index(header) + 1
                self.assertTrue(all(
                    worksheet.cell(row=row, column=column).value in (None, "")
                    for row in range(2, worksheet.max_row + 1)
                ))


if __name__ == "__main__":
    unittest.main()
