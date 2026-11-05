import tempfile
import unittest
from pathlib import Path

from resume_skill_blind_baseline import create_baseline, validate_baseline


class ResumeSkillBlindBaselineTest(unittest.TestCase):
    def test_creates_source_capsule_and_detects_later_source_change(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = root / "backend" / "src" / "main" / "java" / "Example.java"
            source.parent.mkdir(parents=True)
            source.write_text("class Example {}", encoding="utf-8")
            (root / "backend" / "pom.xml").write_text("<project/>", encoding="utf-8")
            data_dir = root / "data"
            data_dir.mkdir()
            (data_dir / "resume-skill-blind-30-manifest.json").write_text(
                '{"datasetSha256":"dataset-hash"}', encoding="utf-8"
            )
            old_gold = root / "old-gold"
            old_gold.mkdir()
            (old_gold / "gold.json").write_text("{}", encoding="utf-8")

            baseline = create_baseline(root, data_dir, old_gold, root / "baseline", [source, root / "backend" / "pom.xml"])

            self.assertTrue((root / "baseline" / "resume-skill-blind-30-backend-source.zip").is_file())
            self.assertEqual(validate_baseline(root, root / "baseline" / "resume-skill-blind-30-baseline.json"), [])
            source.write_text("class Changed {}", encoding="utf-8")
            self.assertIn("SOURCE_HASH_MISMATCH:backend/src/main/java/Example.java", validate_baseline(root, root / "baseline" / "resume-skill-blind-30-baseline.json"))
            self.assertEqual(baseline["datasetSha256"], "dataset-hash")

    def test_detects_old_gold_tree_change(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = root / "backend" / "pom.xml"
            source.parent.mkdir(parents=True)
            source.write_text("<project/>", encoding="utf-8")
            data_dir = root / "data"
            data_dir.mkdir()
            (data_dir / "resume-skill-blind-30-manifest.json").write_text('{"datasetSha256":"dataset-hash"}', encoding="utf-8")
            old_gold = root / "old-gold"
            old_gold.mkdir()
            old_file = old_gold / "gold.json"
            old_file.write_text("{}", encoding="utf-8")
            create_baseline(root, data_dir, old_gold, root / "baseline", [source])
            old_file.write_text('{"changed":true}', encoding="utf-8")

            failures = validate_baseline(root, root / "baseline" / "resume-skill-blind-30-baseline.json")

            self.assertIn("OLD_GOLD_TREE_HASH_MISMATCH", failures)

    def test_refuses_baseline_after_annotation_or_gold_exists(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            data_dir = root / "data"
            data_dir.mkdir()
            (data_dir / "resume-skill-blind-30-manifest.json").write_text(
                '{"datasetSha256":"dataset-hash"}', encoding="utf-8"
            )
            (data_dir / "resume-skill-blind-30-initial-annotations.json").write_text("{}", encoding="utf-8")
            old_gold = root / "old-gold"
            old_gold.mkdir()

            with self.assertRaisesRegex(ValueError, "annotation"):
                create_baseline(root, data_dir, old_gold, root / "baseline", [])

    def test_refuses_to_overwrite_existing_baseline(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            data_dir = root / "data"
            data_dir.mkdir()
            (data_dir / "resume-skill-blind-30-manifest.json").write_text(
                '{"datasetSha256":"dataset-hash"}', encoding="utf-8"
            )
            old_gold = root / "old-gold"
            old_gold.mkdir()
            output = root / "baseline"
            output.mkdir()
            (output / "resume-skill-blind-30-baseline.json").write_text("{}", encoding="utf-8")

            with self.assertRaisesRegex(FileExistsError, "overwrite"):
                create_baseline(root, data_dir, old_gold, output, [])


if __name__ == "__main__":
    unittest.main()
