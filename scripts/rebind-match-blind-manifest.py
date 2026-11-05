"""Rebind a label-free frozen match manifest to newly built runtime artifacts."""

import argparse
import hashlib
import json
import sys
from datetime import datetime, timezone
from pathlib import Path


ARTIFACT_PATHS = {
    "jarSha256": "backend/target/zhigang-backend-1.0.0.jar",
    "capabilityCardsSha256": "backend/src/main/resources/matching/role-capability-cards-v2.json",
    "resumeEvidenceExtractorSha256": "backend/src/main/java/com/zhigang/platform/career/ResumeEvidenceExtractor.java",
    "matchDecisionEngineSha256": "backend/src/main/java/com/zhigang/platform/career/MatchDecisionEngine.java",
}


def file_sha256(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def canonical_sha256(value):
    return hashlib.sha256(json.dumps(
        value, ensure_ascii=False, sort_keys=True, separators=(",", ":")
    ).encode("utf-8")).hexdigest()


def rebind(source_path, output_path, root, dataset_id):
    source_path = Path(source_path)
    output_path = Path(output_path)
    root = Path(root).resolve()
    if output_path.exists():
        raise ValueError(f"refusing to overwrite manifest: {output_path}")
    manifest = json.loads(source_path.read_text(encoding="utf-8"))
    if manifest.get("status") != "FROZEN_FOR_PREDICTION" \
            or manifest.get("containsLabels") is not False:
        raise ValueError("source must be a frozen label-free prediction manifest")
    if len(manifest.get("resumes", [])) != 30 \
            or len(manifest.get("roles", [])) != 7 \
            or len(manifest.get("cases", [])) != 210:
        raise ValueError("source must preserve the official 30x7 matrix")
    manifest["datasetId"] = dataset_id
    manifest["sealedAt"] = datetime.now(timezone.utc).isoformat().replace("+00:00", "Z")
    manifest["artifacts"] = {
        name: file_sha256(root / relative) for name, relative in ARTIFACT_PATHS.items()
    }
    manifest["roleCatalogSha256"] = manifest["artifacts"]["capabilityCardsSha256"]
    manifest.pop("datasetSha256", None)
    manifest["datasetSha256"] = canonical_sha256(manifest)
    output_path.parent.mkdir(parents=True, exist_ok=True)
    output_path.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[1])
    parser.add_argument("--dataset-id", required=True)
    args = parser.parse_args()
    try:
        rebind(args.source, args.output, args.root, args.dataset_id)
        return 0
    except (OSError, json.JSONDecodeError, ValueError) as error:
        print(f"Error: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
