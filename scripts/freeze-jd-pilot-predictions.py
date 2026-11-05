from __future__ import annotations

import argparse
import hashlib
import json
from datetime import datetime, timezone
from pathlib import Path


def file_sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def read_jsonl(path: Path) -> list[dict]:
    return [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines() if line.strip()]


def build_inputs(records: list[dict]) -> list[dict]:
    return [
        {
            "sampleNumber": index,
            "recordId": index,
            "sourceRecordId": record["recordId"],
            "inputText": record["rawText"],
        }
        for index, record in enumerate(records, start=1)
    ]


def write_inputs(data_path: Path, destination: Path) -> dict:
    inputs = build_inputs(read_jsonl(data_path))
    destination.parent.mkdir(parents=True, exist_ok=True)
    destination.write_text(
        "".join(json.dumps(item, ensure_ascii=False) + "\n" for item in inputs),
        encoding="utf-8",
    )
    return {"sampleCount": len(inputs), "inputsSha256": file_sha256(destination)}


def write_seal(inputs_path: Path, predictions_path: Path, seal_path: Path,
               artifact_paths: list[Path], expected_count: int) -> dict:
    inputs = read_jsonl(inputs_path)
    predictions = json.loads(predictions_path.read_text(encoding="utf-8"))
    expected_keys = {str(index) for index in range(1, expected_count + 1)}
    if len(inputs) != expected_count or set(predictions) != expected_keys:
        raise ValueError("试验输入或预测数量不完整")
    artifacts = []
    for path in artifact_paths:
        if not path.is_file():
            raise FileNotFoundError(path)
        artifacts.append({"path": str(path.resolve()), "sha256": file_sha256(path)})
    result = {
        "schemaVersion": "1.0.0",
        "sealedAt": datetime.now(timezone.utc).isoformat(),
        "sampleCount": expected_count,
        "inputsSha256": file_sha256(inputs_path),
        "predictionsSha256": file_sha256(predictions_path),
        "artifacts": artifacts,
    }
    seal_path.parent.mkdir(parents=True, exist_ok=True)
    seal_path.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return result


def main() -> int:
    parser = argparse.ArgumentParser(description="准备并密封 JD 试验集预测")
    subparsers = parser.add_subparsers(dest="command", required=True)
    prepare = subparsers.add_parser("prepare")
    prepare.add_argument("--data", type=Path, required=True)
    prepare.add_argument("--inputs", type=Path, required=True)
    seal = subparsers.add_parser("seal")
    seal.add_argument("--inputs", type=Path, required=True)
    seal.add_argument("--predictions", type=Path, required=True)
    seal.add_argument("--seal", type=Path, required=True)
    seal.add_argument("--artifact", type=Path, action="append", default=[])
    seal.add_argument("--expected-count", type=int, required=True)
    args = parser.parse_args()
    if args.command == "prepare":
        result = write_inputs(args.data, args.inputs)
    else:
        result = write_seal(
            args.inputs, args.predictions, args.seal, args.artifact, args.expected_count)
    print(json.dumps(result, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
