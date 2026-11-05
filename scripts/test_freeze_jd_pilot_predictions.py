import hashlib
import importlib.util
import json
import tempfile
import unittest
from pathlib import Path


SCRIPT = Path(__file__).with_name("freeze-jd-pilot-predictions.py")
SPEC = importlib.util.spec_from_file_location("freeze_jd_pilot_predictions", SCRIPT)
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class FreezeJdPilotPredictionsTest(unittest.TestCase):
    def test_build_inputs_contains_no_gold_or_annotation_fields(self):
        records = [
            {"recordId": "HJD-1", "rawText": "岗位一"},
            {"recordId": "HJD-2", "rawText": "岗位二"},
        ]

        inputs = MODULE.build_inputs(records)

        self.assertEqual([
            {"sampleNumber": 1, "recordId": 1, "sourceRecordId": "HJD-1", "inputText": "岗位一"},
            {"sampleNumber": 2, "recordId": 2, "sourceRecordId": "HJD-2", "inputText": "岗位二"},
        ], inputs)
        self.assertFalse(any(
            "gold" in key.lower() or "标注" in key or "expected" in key.lower()
            for item in inputs for key in item
        ))

    def test_seal_binds_inputs_predictions_and_artifact_files(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            inputs = root / "inputs.jsonl"
            predictions = root / "predictions.json"
            artifact = root / "artifact.bin"
            seal = root / "seal.json"
            inputs.write_text(
                '{"sampleNumber":1,"recordId":1,"sourceRecordId":"HJD-1","inputText":"岗位"}\n',
                encoding="utf-8",
            )
            predictions.write_text('{"1":{"facts":[]}}', encoding="utf-8")
            artifact.write_bytes(b"artifact")

            result = MODULE.write_seal(inputs, predictions, seal, [artifact], expected_count=1)

            self.assertEqual(1, result["sampleCount"])
            self.assertEqual(hashlib.sha256(inputs.read_bytes()).hexdigest(), result["inputsSha256"])
            self.assertEqual(hashlib.sha256(predictions.read_bytes()).hexdigest(), result["predictionsSha256"])
            self.assertEqual(hashlib.sha256(artifact.read_bytes()).hexdigest(), result["artifacts"][0]["sha256"])
            self.assertEqual(result, json.loads(seal.read_text(encoding="utf-8")))


if __name__ == "__main__":
    unittest.main()
