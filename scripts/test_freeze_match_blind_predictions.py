import hashlib
import importlib.util
import json
import os
import tempfile
import unittest
from pathlib import Path


SCRIPT = Path(__file__).with_name("freeze-match-blind-predictions.py")


def load_module():
    spec = importlib.util.spec_from_file_location("freeze_match_blind_predictions", SCRIPT)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def prediction_payload(manifest):
    items = [
        {"caseId": f"c{index:03d}", "applicationState": "需先补关键能力"}
        for index in range(210)
    ]
    return {
        "schemaVersion": "match-blind-predictions-v1.0",
        "datasetId": "d1", "datasetSha256": "a" * 64,
        "artifactHashes": {},
        "manifestFileSha256": hashlib.sha256(manifest.read_bytes()).hexdigest(),
        "resumeCount": 30, "roleCount": 7, "caseCount": 210,
        "stateCounts": [
            {"state": "需先补关键能力", "count": 210},
            {"state": "接近可申请", "count": 0},
            {"state": "可申请实习/校招", "count": 0},
        ],
        "scoringPolicyVersions": ["ordered-capability-v2.4"],
        "runtime": {"selfLaunchedFrozenJar": True}, "predictions": items,
    }


class FreezeMatchBlindPredictionsTest(unittest.TestCase):
    def test_freezes_and_verifies_prediction_bytes(self):
        module = load_module()
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            manifest = root / "manifest.json"
            predictions = root / "predictions.json"
            freeze = root / "freeze.json"
            manifest.write_text(json.dumps({"datasetId": "d1"}), encoding="utf-8")
            predictions.write_text(json.dumps(prediction_payload(manifest)), encoding="utf-8")

            record = module.freeze(predictions, manifest, freeze, "secret")
            self.assertEqual(64, len(record["predictionSha256"]))
            self.assertTrue(module.verify_freeze(record, predictions, manifest, "secret"))

    def test_rejects_tampered_predictions(self):
        module = load_module()
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            manifest = root / "manifest.json"
            predictions = root / "predictions.json"
            freeze = root / "freeze.json"
            manifest.write_text(json.dumps({"datasetId": "d1"}), encoding="utf-8")
            predictions.write_text(json.dumps(prediction_payload(manifest)), encoding="utf-8")
            module.freeze(predictions, manifest, freeze, "secret")
            predictions.write_text('{"changed":true}', encoding="utf-8")

            with self.assertRaisesRegex(module.FreezeError, "hash"):
                module.verify_freeze(json.loads(freeze.read_text()), predictions, manifest, "secret")

    def test_rejects_inconsistent_case_count(self):
        module = load_module()
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            manifest = root / "manifest.json"
            predictions = root / "predictions.json"
            manifest.write_text(json.dumps({"datasetId": "d1"}), encoding="utf-8")
            payload = prediction_payload(manifest)
            payload["predictions"].pop()
            predictions.write_text(json.dumps(payload), encoding="utf-8")

            with self.assertRaisesRegex(module.FreezeError, "210"):
                module.freeze(predictions, manifest, root / "freeze.json", "secret")


if __name__ == "__main__":
    unittest.main()
