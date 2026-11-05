import importlib.util
import sys
import tempfile
import unittest
from pathlib import Path

from openpyxl import load_workbook


MODULE_PATH = Path(__file__).with_name("create-resume-skill-gold-dataset.py")


class ResumeSkillGoldDatasetTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        spec = importlib.util.spec_from_file_location("resume_skill_gold_dataset", MODULE_PATH)
        cls.module = importlib.util.module_from_spec(spec)
        sys.modules[spec.name] = cls.module
        spec.loader.exec_module(cls.module)

    def test_selects_numbered_curated_docx_and_pdf(self):
        with tempfile.TemporaryDirectory() as directory:
            sample_dir = Path(directory) / "09_Sample"
            sample_dir.mkdir()
            (sample_dir / "09_Sample.docx").write_bytes(b"docx")
            (sample_dir / "09_Sample.pdf").write_bytes(b"pdf")
            (sample_dir / "original.pdf").write_bytes(b"original")

            sample = self.module.select_sample_files(sample_dir)

            self.assertEqual(sample.docx.name, "09_Sample.docx")
            self.assertEqual(sample.pdf.name, "09_Sample.pdf")

    def test_rejects_multiple_numbered_curated_docx_candidates(self):
        with tempfile.TemporaryDirectory() as directory:
            sample_dir = Path(directory) / "19_Sample"
            sample_dir.mkdir()
            (sample_dir / "19_Sample.docx").write_bytes(b"one")
            (sample_dir / "19_Alternative.docx").write_bytes(b"two")
            (sample_dir / "19_Sample.pdf").write_bytes(b"pdf")

            with self.assertRaisesRegex(ValueError, "DOCX"):
                self.module.select_sample_files(sample_dir)

    def test_manifest_hash_is_stable_and_annotation_has_no_prediction_fields(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            resume_dir = root / "resumes" / "01_Sample"
            resume_dir.mkdir(parents=True)
            (resume_dir / "01_Sample.docx").write_bytes(b"docx-content")
            (resume_dir / "01_Sample.pdf").write_bytes(b"pdf-content")

            records = self.module.build_manifest_records(root / "resumes")
            first = self.module.dataset_hash(records)
            second = self.module.dataset_hash(records)
            headers, rows = self.module.annotation_rows(records)

            self.assertEqual(first, second)
            self.assertEqual(len(rows), 1)
            self.assertIn("人工标准技能（用|分隔）", headers)
            self.assertIn("人工原词与标准词映射", headers)
            self.assertNotIn("程序预测技能", headers)
            self.assertNotIn("系统命中", headers)

    def test_workbook_contains_guidance_and_review_controls(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            resume_dir = root / "resumes" / "01_Sample"
            resume_dir.mkdir(parents=True)
            (resume_dir / "01_Sample.docx").write_bytes(b"not-a-real-docx")
            (resume_dir / "01_Sample.pdf").write_bytes(b"pdf-content")
            records = self.module.build_manifest_records(root / "resumes")
            output = root / "annotation.xlsx"

            self.module.write_annotation_workbook(output, records, include_text=False)

            workbook = load_workbook(output)
            self.assertEqual(workbook.sheetnames, ["标注说明", "技能人工标注"])
            sheet = workbook["技能人工标注"]
            self.assertEqual(sheet["F2"].value, "未标注")
            self.assertEqual(len(sheet.data_validations.dataValidation), 2)

    def test_initial_annotations_fill_candidate_fields_without_marking_reviewed(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            resume_dir = root / "resumes" / "01_Sample"
            resume_dir.mkdir(parents=True)
            (resume_dir / "01_Sample.docx").write_bytes(b"not-a-real-docx")
            (resume_dir / "01_Sample.pdf").write_bytes(b"pdf-content")
            records = self.module.build_manifest_records(root / "resumes")
            annotations = {
                "01": {
                    "skills": ["Vue", "TypeScript"],
                    "mappings": ["Vue 3=>Vue"],
                    "evidence": "专业技能",
                    "uncertain": "",
                    "notes": "独立初标",
                }
            }

            _, rows = self.module.annotation_rows(records, include_text=False, annotations=annotations)

            self.assertEqual(rows[0][5], "已完成")
            self.assertEqual(rows[0][6], "Vue|TypeScript")
            self.assertEqual(rows[0][7], "Vue 3=>Vue")
            self.assertEqual(rows[0][11], "Codex初标")
            self.assertEqual(rows[0][13], "待复核")


if __name__ == "__main__":
    unittest.main()
