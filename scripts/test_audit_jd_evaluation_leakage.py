import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

from openpyxl import Workbook


SCRIPT = Path(__file__).with_name("audit-jd-evaluation-leakage.py")


class JdEvaluationLeakageAuditTest(unittest.TestCase):
    def test_jsonl_keeps_unicode_line_separators_inside_json_strings(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            candidate = root / "candidate.jsonl"
            exposed = root / "exposed.jsonl"
            report = root / "report.json"
            text = "job duties\u2028requirements\u2029Python"
            candidate.write_text(
                json.dumps({"recordId": 1, "inputText": text}, ensure_ascii=False),
                encoding="utf-8",
            )
            exposed.write_text(
                json.dumps({"recordId": 2, "rawText": text}, ensure_ascii=False),
                encoding="utf-8",
            )

            completed = subprocess.run(
                [sys.executable, str(SCRIPT), "--candidate", str(candidate), "--exposed", str(exposed),
                 "--report", str(report)], capture_output=True, text=True, check=False,
            )

            self.assertEqual(2, completed.returncode, completed.stderr)
            self.assertEqual("exact", json.loads(report.read_text(encoding="utf-8"))["findings"][0]["matchType"])

    def test_discovers_jd_field_from_utf16_jsonl(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            exposed_root = root / "exposed"
            exposed_root.mkdir()
            candidate = root / "candidate.jsonl"
            report = root / "report.json"
            text = "\u5c97\u4f4d\u804c\u8d23\uff1a\u8d1f\u8d23 RAG \u5e94\u7528\u5f00\u53d1\uff1b\u4efb\u804c\u8981\u6c42\uff1a\u719f\u6089 Python"
            candidate.write_text(
                json.dumps({"recordId": 1, "inputText": text}, ensure_ascii=False),
                encoding="utf-8",
            )
            (exposed_root / "rows.jsonl").write_text(
                json.dumps({"recordId": 2, "jd": text}, ensure_ascii=False),
                encoding="utf-16",
            )

            completed = subprocess.run(
                [sys.executable, str(SCRIPT), "--candidate", str(candidate),
                 "--exposed-root", str(exposed_root), "--report", str(report)],
                capture_output=True, text=True, check=False,
            )

            self.assertEqual(2, completed.returncode, completed.stderr)
            payload = json.loads(report.read_text(encoding="utf-8"))
            self.assertEqual(1, payload["exposedCount"])
            self.assertEqual({".jsonl": 1}, payload["exposedFilesByType"])
            self.assertEqual("exact", payload["findings"][0]["matchType"])

    def test_discovers_known_plain_text_jd_example(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = Path(__file__).parents[1] / "test-data" / "examples" / "jd-rag-input.txt"
            text = source.read_text(encoding="utf-8")
            candidate = root / "candidate.jsonl"
            report = root / "report.json"
            candidate.write_text(
                json.dumps({"recordId": 1, "inputText": text}, ensure_ascii=False),
                encoding="utf-8",
            )

            completed = subprocess.run(
                [sys.executable, str(SCRIPT), "--candidate", str(candidate),
                 "--exposed-root", str(source), "--report", str(report)],
                capture_output=True, text=True, check=False,
            )

            self.assertEqual(2, completed.returncode, completed.stderr)
            payload = json.loads(report.read_text(encoding="utf-8"))
            self.assertEqual(1, payload["exposedCount"])
            self.assertEqual({".txt": 1}, payload["exposedFilesByType"])
            self.assertEqual("exact", payload["findings"][0]["matchType"])

    def test_discovers_raw_document_text_from_mysql_dump(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            exposed_root = root / "exposed"
            exposed_root.mkdir()
            candidate = root / "candidate.jsonl"
            report = root / "report.json"
            duplicate_text = "岗位职责：负责大模型平台开发；任职要求：熟悉 Python 和数据库。"
            candidate.write_text(json.dumps({
                "recordId": "candidate",
                "rawText": duplicate_text,
            }, ensure_ascii=False), encoding="utf-8")
            (exposed_root / "backup.sql").write_text(
                "CREATE TABLE `raw_document` (\n"
                "  `id` bigint NOT NULL,\n"
                "  `document_title` varchar(255) NOT NULL,\n"
                "  `raw_text` mediumtext NOT NULL,\n"
                "  `content_hash` char(64) NOT NULL\n"
                ");\n"
                "INSERT INTO `raw_document` VALUES "
                f"(1,'Java 工程师','{duplicate_text}','hash-1'),"
                "(2,'数据工程师','职责包含 SQL\\n要求包含 Docker','hash-2');\n",
                encoding="utf-8",
            )

            completed = subprocess.run(
                [sys.executable, str(SCRIPT), "--candidate", str(candidate),
                 "--exposed-root", str(exposed_root), "--report", str(report)],
                capture_output=True, text=True, check=False,
            )

            self.assertEqual(2, completed.returncode, completed.stderr)
            payload = json.loads(report.read_text(encoding="utf-8"))
            self.assertEqual(2, payload["exposedCount"])
            self.assertEqual({".sql": 1}, payload["exposedFilesByType"])
            self.assertEqual("exact", payload["findings"][0]["matchType"])

    def test_discovers_exposed_jd_text_from_json_csv_and_xlsx_roots(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            exposed_root = root / "exposed"
            exposed_root.mkdir()
            candidate = root / "candidate.jsonl"
            report = root / "report.json"
            candidate.write_text(json.dumps({
                "recordId": "candidate",
                "rawText": "岗位职责：负责大模型安全测试。任职要求：熟悉 Python 和提示注入检测。",
            }, ensure_ascii=False), encoding="utf-8")
            (exposed_root / "nested.json").write_text(json.dumps({
                "records": [{"recordId": "json-1", "chineseOriginalText": "JSON 中的历史岗位职责和任职要求文本"}],
            }, ensure_ascii=False), encoding="utf-8")
            (exposed_root / "rows.csv").write_text(
                "recordId,岗位正文（中文原文）\ncsv-1,CSV 中的历史岗位职责和任职要求文本\n", encoding="utf-8",
            )
            workbook = Workbook()
            sheet = workbook.active
            sheet.append(["recordId", "JD原文"])
            sheet.append(["xlsx-1", "XLSX 中的历史岗位职责和任职要求文本"])
            workbook.save(exposed_root / "rows.xlsx")

            completed = subprocess.run(
                [sys.executable, str(SCRIPT), "--candidate", str(candidate),
                 "--exposed-root", str(exposed_root), "--report", str(report)],
                capture_output=True, text=True, check=False,
            )

            self.assertEqual(0, completed.returncode, completed.stderr)
            payload = json.loads(report.read_text(encoding="utf-8"))
            self.assertEqual(3, payload["exposedCount"])
            self.assertEqual({".csv": 1, ".json": 1, ".xlsx": 1}, payload["exposedFilesByType"])

    def test_reports_exact_and_near_duplicate_candidates(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            candidate = root / "candidate.jsonl"
            exposed = root / "exposed.jsonl"
            report = root / "report.json"
            candidate.write_text("\n".join([
                json.dumps({"recordId": 1, "inputText": "任职要求：熟悉 Python、SQL 和 Docker。"}, ensure_ascii=False),
                json.dumps({"recordId": 2, "inputText": "岗位职责：负责数据平台建设；任职要求：掌握 Java。"}, ensure_ascii=False),
            ]), encoding="utf-8")
            exposed.write_text("\n".join([
                json.dumps({"recordId": 9, "inputText": "任职要求：熟悉 Python、SQL 和 Docker。"}, ensure_ascii=False),
                json.dumps({"recordId": 10, "inputText": "岗位职责：负责数据平台搭建，任职要求：掌握 Java。"}, ensure_ascii=False),
            ]), encoding="utf-8")

            completed = subprocess.run(
                [sys.executable, str(SCRIPT), "--candidate", str(candidate), "--exposed", str(exposed),
                 "--report", str(report), "--near-threshold", "0.80"],
                capture_output=True, text=True, check=False,
            )

            self.assertEqual(2, completed.returncode, completed.stderr)
            findings = json.loads(report.read_text(encoding="utf-8"))["findings"]
            self.assertEqual("exact", findings[0]["matchType"])
            self.assertEqual("near", findings[1]["matchType"])

    def test_reads_real_corpus_raw_text_field(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            candidate = root / "candidate.jsonl"
            exposed = root / "exposed.jsonl"
            report = root / "report.json"
            text = "岗位职责：负责推荐系统；任职要求：熟悉 Python。"
            candidate.write_text(json.dumps({"recordId": 1, "inputText": text}, ensure_ascii=False), encoding="utf-8")
            exposed.write_text(json.dumps({"recordId": 2, "rawText": text}, ensure_ascii=False), encoding="utf-8")

            completed = subprocess.run(
                [sys.executable, str(SCRIPT), "--candidate", str(candidate), "--exposed", str(exposed),
                 "--report", str(report)], capture_output=True, text=True, check=False,
            )

            self.assertEqual(2, completed.returncode, completed.stderr)
            self.assertEqual("exact", json.loads(report.read_text(encoding="utf-8"))["findings"][0]["matchType"])

    def test_rejects_duplicates_inside_candidate_set(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            candidate = root / "candidate.jsonl"
            exposed = root / "exposed.jsonl"
            report = root / "report.json"
            candidate.write_text("\n".join([
                json.dumps({"recordId": 1, "inputText": "任职要求：熟悉 Java 和 Spring Boot。"}, ensure_ascii=False),
                json.dumps({"recordId": 2, "inputText": "任职要求：熟悉 Java、Spring Boot。"}, ensure_ascii=False),
            ]), encoding="utf-8")
            exposed.write_text(json.dumps({"recordId": 9, "rawText": "完全不同的岗位文本"}, ensure_ascii=False), encoding="utf-8")

            completed = subprocess.run(
                [sys.executable, str(SCRIPT), "--candidate", str(candidate), "--exposed", str(exposed),
                 "--report", str(report)], capture_output=True, text=True, check=False,
            )

            self.assertEqual(2, completed.returncode, completed.stderr)
            finding = json.loads(report.read_text(encoding="utf-8"))["findings"][0]
            self.assertEqual("candidate-internal", finding["scope"])


if __name__ == "__main__":
    unittest.main()
