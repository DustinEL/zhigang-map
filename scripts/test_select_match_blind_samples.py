import hashlib
import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
SELECTOR = ROOT / "scripts" / "select-match-blind-samples.py"
FIXED_ROLE_IDS = {2, 3, 4, 5, 7, 9, 12, 13}
FORBIDDEN_FIELDS = ("predicted", "readiness", "score", "历史结果")


class MatchBlindSampleSelectorTest(unittest.TestCase):
    def setUp(self):
        self.temp_directory = tempfile.TemporaryDirectory()
        output_root = Path(self.temp_directory.name)
        self.manifest_path = output_root / "manifest.json"
        self.annotation_path = output_root / "annotation.md"
        subprocess.run(
            [
                sys.executable,
                str(SELECTOR),
                "--root",
                str(ROOT),
                "--manifest",
                str(self.manifest_path),
                "--annotation",
                str(self.annotation_path),
            ],
            check=True,
            cwd=ROOT,
            capture_output=True,
            text=True,
            encoding="utf-8",
        )
        self.manifest = json.loads(self.manifest_path.read_text(encoding="utf-8"))

    def tearDown(self):
        self.temp_directory.cleanup()

    def test_selects_twelve_non_calibration_resumes_across_required_strata(self):
        resumes = self.manifest["resumes"]
        resume_ids = [resume["resumeId"] for resume in resumes]
        strata = {resume["stratum"] for resume in resumes}

        self.assertEqual(len(resumes), 12)
        self.assertEqual(len(set(resume_ids)), 12)
        self.assertTrue(all(8 <= int(resume_id) <= 30 for resume_id in resume_ids))
        self.assertEqual(
            strata,
            {"java-backend", "ai-llm", "data-ml", "frontend-fullstack", "cpp-systems"},
        )

    def test_builds_ninety_six_unique_cases_for_the_eight_fixed_roles(self):
        roles = self.manifest["roles"]
        cases = self.manifest["cases"]
        case_ids = [case["caseId"] for case in cases]
        resume_role_pairs = {(case["resumeId"], case["roleId"]) for case in cases}

        self.assertEqual({role["roleId"] for role in roles}, FIXED_ROLE_IDS)
        self.assertEqual(len(roles), 8)
        self.assertEqual(len(cases), 96)
        self.assertEqual(len(set(case_ids)), 96)
        self.assertEqual(len(resume_role_pairs), 96)

    def test_records_real_docx_hashes_and_a_reproducible_dataset_hash(self):
        for resume in self.manifest["resumes"]:
            docx = resume["docx"]
            source = ROOT / Path(docx["path"])
            self.assertTrue(source.is_file())
            self.assertEqual(docx["sha256"], hashlib.sha256(source.read_bytes()).hexdigest())
            self.assertEqual(docx["bytes"], source.stat().st_size)

        hash_input = dict(self.manifest)
        dataset_hash = hash_input.pop("datasetSha256")
        canonical = json.dumps(
            hash_input,
            ensure_ascii=False,
            sort_keys=True,
            separators=(",", ":"),
        ).encode("utf-8")
        self.assertEqual(dataset_hash, hashlib.sha256(canonical).hexdigest())

    def test_manifest_and_annotation_are_free_of_prediction_data(self):
        serialized_manifest = json.dumps(self.manifest, ensure_ascii=False).lower()
        annotation = self.annotation_path.read_text(encoding="utf-8")
        annotation_lower = annotation.lower()

        for forbidden in FORBIDDEN_FIELDS:
            self.assertNotIn(forbidden, serialized_manifest)
            self.assertNotIn(forbidden, annotation_lower)

        data_rows = [
            line
            for line in annotation.splitlines()
            if line.startswith("| MB-")
        ]
        self.assertEqual(len(data_rows), 96)
        for row in data_rows:
            columns = [column.strip() for column in row.strip("|").split("|")]
            self.assertEqual(len(columns), 8)
            self.assertEqual(columns[5], "")


if __name__ == "__main__":
    unittest.main()
