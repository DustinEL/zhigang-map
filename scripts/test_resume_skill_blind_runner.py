import json
import tempfile
import unittest
from pathlib import Path

from resume_skill_blind_runner import prepare_run


class ResumeSkillBlindRunnerTest(unittest.TestCase):
    def test_refuses_existing_first_report_or_log(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            report = root / "first-run-report.json"
            log = root / "first-run-console.log"
            report.write_text("{}", encoding="utf-8")

            with self.assertRaisesRegex(FileExistsError, "first-run"):
                prepare_run(root, root / "baseline.json", root / "manifest.json", root / "gold.json", report, log)

    def test_rejects_dataset_or_source_hash_mismatch_before_extracting(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            baseline = root / "baseline.json"
            manifest = root / "manifest.json"
            gold = root / "gold.json"
            baseline.write_text(json.dumps({"datasetSha256": "A", "sourceTreeSha256": "SOURCE", "sourceCapsule": {"path": "missing.zip", "sha256": "x"}}), encoding="utf-8")
            manifest.write_text(json.dumps({"datasetSha256": "B"}), encoding="utf-8")
            gold.write_text(json.dumps({"datasetSha256": "A", "baselineSourceTreeSha256": "OTHER"}), encoding="utf-8")

            with self.assertRaisesRegex(ValueError, "hash binding"):
                prepare_run(root, baseline, manifest, gold, root / "report.json", root / "log.txt")


if __name__ == "__main__":
    unittest.main()
