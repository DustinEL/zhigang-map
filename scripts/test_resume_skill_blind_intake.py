import tempfile
import unittest
from pathlib import Path

from resume_skill_blind_intake import (
    admission_failures,
    audit_independence,
    audit_dataset,
    code_line_ratio,
    identity_from_text,
    publish_intake,
    select_sample_files,
    shingle_jaccard,
)


class ResumeSkillBlindIntakeTest(unittest.TestCase):
    def test_selects_one_numbered_docx_pdf_and_source_zip(self):
        with tempfile.TemporaryDirectory() as directory:
            sample_dir = Path(directory) / "01_Person_Backend"
            sample_dir.mkdir()
            for name in ("01_Person_Backend.docx", "01_Person_Backend.pdf", "source.zip"):
                (sample_dir / name).write_bytes(b"content")

            sample = select_sample_files(sample_dir)

            self.assertEqual(sample.sample_id, "01")
            self.assertEqual(sample.docx.name, "01_Person_Backend.docx")
            self.assertEqual(sample.pdf.name, "01_Person_Backend.pdf")
            self.assertEqual(sample.source_zip.name, "source.zip")

    def test_rejects_ambiguous_numbered_files(self):
        with tempfile.TemporaryDirectory() as directory:
            sample_dir = Path(directory) / "01_Person"
            sample_dir.mkdir()
            for name in ("01_Person.docx", "01_Other.docx", "01_Person.pdf", "source.zip"):
                (sample_dir / name).write_bytes(b"content")

            with self.assertRaisesRegex(ValueError, "DOCX"):
                select_sample_files(sample_dir)

    def test_identity_match_blocks_differently_formatted_same_person(self):
        new_records = [{"sampleId": "10", "identity": {"emails": ["Same@Example.com"], "github": ["https://github.com/person/"]}, "text": "new words"}]
        old_records = [{"sampleId": "26", "identity": {"emails": ["same@example.com"], "github": ["github.com/person"]}, "text": "different words"}]

        result = audit_independence(new_records, old_records)

        self.assertEqual(result["status"], "FAIL")
        self.assertEqual(result["identityDuplicates"][0]["newSampleId"], "10")
        self.assertEqual(result["identityDuplicates"][0]["oldSampleId"], "26")

    def test_shared_dependency_repository_is_not_candidate_identity(self):
        first = identity_from_text(
            "公开来源\nhttps://github.com/first/person\n公开履历正文\n"
            "https://github.com/vitejs/vite-plugin-react"
        )
        second = identity_from_text(
            "公开来源\nhttps://github.com/second/person\n公开履历正文\n"
            "https://github.com/vitejs/vite-plugin-react"
        )

        result = audit_independence(
            [{"sampleId": "01", "identity": first, "text": "first resume"}],
            [{"sampleId": "31", "identity": second, "text": "second resume"}],
        )

        self.assertEqual(result["identityDuplicates"], [])

    def test_near_duplicate_thresholds_are_classified(self):
        shared = "alpha beta gamma delta epsilon zeta eta theta iota kappa"
        self.assertEqual(shingle_jaccard(shared, shared), 1.0)
        records = [{"sampleId": "01", "identity": {}, "text": shared}]
        old = [{"sampleId": "31", "identity": {}, "text": shared}]

        result = audit_independence(records, old)

        self.assertEqual(result["nearDuplicates"][0]["decision"], "REJECT")

    def test_code_pollution_and_resume_sections_are_hard_gates(self):
        text = "\n".join([
            "Skills", "Python Java", "Projects", "Built an API",
            "const a = 1;", "import x from 'x';", "package.json", "npm install",
        ])
        self.assertEqual(code_line_ratio(text), 0.5)

        failures = admission_failures(text, text, pdf_similarity=0.99)

        self.assertIn("CODE_CONTENT_OVER_10_PERCENT", failures)
        self.assertNotIn("INSUFFICIENT_RESUME_SECTIONS", failures)

    def test_javascript_skill_names_are_not_counted_as_source_files(self):
        text = "Skills\nNode.js\nReact.js\nProjects\nBuilt an API"

        self.assertEqual(code_line_ratio(text), 0.0)

    def test_pdf_similarity_below_point_95_is_rejected(self):
        text = "Skills\nPython\nProjects\nBuilt an API"

        failures = admission_failures(text, text, pdf_similarity=0.9499)

        self.assertIn("DOCX_PDF_CONTENT_MISMATCH", failures)

    def test_failed_audit_is_preserved_without_publishing_manifest(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory)
            manifest = {"datasetSha256": "abc123", "samples": []}
            independence = {"status": "FAIL", "identityDuplicates": [{"newSampleId": "10"}]}
            eligibility = {"status": "PASS", "samples": []}

            audit_dir = publish_intake(output, manifest, independence, eligibility)

            self.assertTrue((audit_dir / "independence-audit.json").is_file())
            self.assertFalse((output / "resume-skill-blind-30-manifest.json").exists())
            self.assertFalse((output / "resume-skill-blind-30-annotation.xlsx").exists())

    def test_changed_audit_result_gets_a_new_immutable_run_directory(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory)
            manifest = {"datasetSha256": "same-data", "samples": []}
            eligibility = {"status": "FAIL", "samples": []}

            first = publish_intake(output, manifest, {"status": "FAIL", "reason": "old"}, eligibility)
            second = publish_intake(output, manifest, {"status": "FAIL", "reason": "corrected"}, eligibility)

            self.assertNotEqual(first, second)
            self.assertTrue(first.is_dir())
            self.assertTrue(second.is_dir())

    def test_passed_audit_publishes_manifest_without_overwrite(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory)
            manifest = {"datasetSha256": "pass123", "samples": []}
            independence = {"status": "PASS", "identityDuplicates": [], "nearDuplicates": []}
            eligibility = {"status": "PASS", "samples": []}

            publish_intake(output, manifest, independence, eligibility)

            published = output / "resume-skill-blind-30-manifest.json"
            self.assertTrue(published.is_file())
            self.assertTrue((output / "resume-skill-blind-30-protocol.md").is_file())
            self.assertTrue((output / "resume-skill-blind-30-annotation-guidelines.md").is_file())
            with self.assertRaisesRegex(FileExistsError, "refusing to overwrite"):
                publish_intake(output, manifest, independence, eligibility)

    def test_manual_near_duplicate_blocks_publication_until_reviewed(self):
        new = [{"sampleId": "01", "identity": {}, "text": "a b c d e f g h i j k l m n o p q r s t"}]
        old = [{"sampleId": "31", "identity": {}, "text": "a b c d e f g h i j k l m n o p q r x y"}]

        result = audit_independence(new, old)

        self.assertEqual(result["nearDuplicates"][0]["decision"], "MANUAL_REVIEW")
        self.assertEqual(result["status"], "REVIEW_REQUIRED")

    def test_audit_dataset_rejects_non_continuous_ids_before_reading_documents(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "01_One").mkdir()
            (root / "03_Three").mkdir()

            with self.assertRaisesRegex(ValueError, "连续"):
                audit_dataset(root, None, root, expected_count=2)


if __name__ == "__main__":
    unittest.main()
