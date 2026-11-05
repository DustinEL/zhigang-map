"""Create and verify an externally keyed pre-Gold prediction freeze record."""

import argparse
import hashlib
import hmac
import json
import os
import sys
from datetime import datetime, timezone
from pathlib import Path


class FreezeError(ValueError):
    pass


def sha256(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def canonical(value):
    return json.dumps(value, ensure_ascii=False, sort_keys=True,
                      separators=(",", ":")).encode("utf-8")


def _signature(record, key):
    unsigned = dict(record)
    unsigned.pop("signature", None)
    return hmac.new(key.encode("utf-8"), canonical(unsigned), hashlib.sha256).hexdigest()


def freeze(predictions_path, manifest_path, output_path, key):
    predictions_path = Path(predictions_path)
    manifest_path = Path(manifest_path)
    output_path = Path(output_path)
    if output_path.exists():
        raise FreezeError(f"refusing to overwrite freeze record: {output_path}")
    if not key:
        raise FreezeError("freeze key must not be empty")
    predictions = json.loads(predictions_path.read_text(encoding="utf-8"))
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    if predictions.get("schemaVersion") != "match-blind-predictions-v1.0":
        raise FreezeError("prediction schema is not the official runner schema")
    if predictions.get("manifestFileSha256") != sha256(manifest_path):
        raise FreezeError("prediction manifest hash does not match manifest file")
    if predictions.get("datasetId") != manifest.get("datasetId"):
        raise FreezeError("prediction dataset binding does not match manifest")
    if predictions.get("runtime", {}).get("selfLaunchedFrozenJar") is not True:
        raise FreezeError("prediction runtime is not the frozen runner")
    if predictions.get("resumeCount") != 30 or predictions.get("roleCount") != 7 \
            or predictions.get("caseCount") != 210 \
            or len(predictions.get("predictions", [])) != 210:
        raise FreezeError("prediction runner output must contain exactly 210 cases")
    if not isinstance(predictions.get("scoringPolicyVersions"), list) \
            or len(predictions["scoringPolicyVersions"]) != 1:
        raise FreezeError("prediction policy version proof is invalid")
    record = {
        "schemaVersion": "match-blind-prediction-freeze-v1.0",
        "datasetId": predictions["datasetId"],
        "datasetSha256": predictions.get("datasetSha256"),
        "manifestFileSha256": predictions["manifestFileSha256"],
        "predictionSha256": sha256(predictions_path),
        "caseCount": predictions.get("caseCount"),
        "scoringPolicyVersions": predictions["scoringPolicyVersions"],
        "frozenAt": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
    }
    record["signature"] = _signature(record, key)
    output_path.parent.mkdir(parents=True, exist_ok=True)
    output_path.write_text(json.dumps(record, ensure_ascii=False, indent=2) + "\n",
                           encoding="utf-8")
    return record


def verify_freeze(record, predictions_path, manifest_path, key):
    if not key or record.get("schemaVersion") != "match-blind-prediction-freeze-v1.0":
        raise FreezeError("prediction freeze record schema or key is invalid")
    if not hmac.compare_digest(record.get("signature", ""), _signature(record, key)):
        raise FreezeError("prediction freeze signature is invalid")
    if record.get("predictionSha256") != sha256(predictions_path):
        raise FreezeError("prediction freeze hash does not match predictions")
    if record.get("manifestFileSha256") != sha256(manifest_path):
        raise FreezeError("prediction freeze manifest hash does not match manifest")
    return True


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--predictions", type=Path, required=True)
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--key-env", default="MATCH_BLIND_FREEZE_KEY")
    args = parser.parse_args()
    try:
        freeze(args.predictions, args.manifest, args.output,
               os.environ.get(args.key_env, ""))
        return 0
    except (OSError, json.JSONDecodeError, FreezeError) as error:
        print(f"Error: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
