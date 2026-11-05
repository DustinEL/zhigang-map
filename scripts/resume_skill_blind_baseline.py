from __future__ import annotations

import hashlib
import json
from pathlib import Path
from zipfile import ZIP_DEFLATED, ZipFile


BASELINE_NAME = "resume-skill-blind-30-baseline.json"
CAPSULE_NAME = "resume-skill-blind-30-backend-source.zip"


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def tree_records(root: Path) -> list[dict]:
    if not root.exists():
        return []
    return [
        {"path": path.relative_to(root).as_posix(), "sha256": sha256_file(path), "bytes": path.stat().st_size}
        for path in sorted(item for item in root.rglob("*") if item.is_file())
    ]


def records_hash(records: list[dict]) -> str:
    payload = json.dumps(records, sort_keys=True, separators=(",", ":"))
    return hashlib.sha256(payload.encode("utf-8")).hexdigest()


def create_baseline(project_root: Path, data_dir: Path, old_gold_dir: Path, output_dir: Path,
                    source_files: list[Path], metadata: dict | None = None) -> dict:
    annotation_path = data_dir / "resume-skill-blind-30-initial-annotations.json"
    gold_path = data_dir / "resume-skill-blind-30-gold-v1.json"
    report_path = data_dir / "results" / "first-run-report.json"
    if annotation_path.exists():
        raise ValueError("cannot create baseline after annotation exists")
    if gold_path.exists():
        raise ValueError("cannot create baseline after gold exists")
    if report_path.exists():
        raise ValueError("cannot create baseline after first report exists")

    manifest_path = data_dir / "resume-skill-blind-30-manifest.json"
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    baseline_path = output_dir / BASELINE_NAME
    capsule_path = output_dir / CAPSULE_NAME
    if baseline_path.exists() or capsule_path.exists():
        raise FileExistsError(f"refusing to overwrite existing baseline in {output_dir}")

    normalized_files = sorted({path.resolve() for path in source_files})
    source_records = []
    for path in normalized_files:
        if not path.is_file():
            raise ValueError(f"baseline source file is missing: {path}")
        source_records.append({
            "path": path.relative_to(project_root.resolve()).as_posix(),
            "sha256": sha256_file(path),
            "bytes": path.stat().st_size,
        })

    output_dir.mkdir(parents=True, exist_ok=True)
    with ZipFile(capsule_path, "x", compression=ZIP_DEFLATED) as archive:
        for record in source_records:
            archive.write(project_root / record["path"], record["path"])

    old_gold_records = tree_records(old_gold_dir)
    baseline = {
        "version": "resume-skill-blind-baseline-v1",
        "datasetSha256": manifest["datasetSha256"],
        "manifestPath": manifest_path.relative_to(project_root).as_posix(),
        "manifestSha256": sha256_file(manifest_path),
        "sourceFiles": source_records,
        "sourceTreeSha256": records_hash(source_records),
        "sourceCapsule": {"path": capsule_path.relative_to(project_root).as_posix(), "sha256": sha256_file(capsule_path)},
        "oldGoldTreeSha256": records_hash(old_gold_records),
        "oldGoldRoot": old_gold_dir.relative_to(project_root).as_posix(),
        "oldGoldFiles": old_gold_records,
        "metadata": metadata or {},
        "initialAnnotationsAbsent": True,
        "goldAbsent": True,
        "firstReportAbsent": True,
    }
    try:
        with baseline_path.open("x", encoding="utf-8") as stream:
            json.dump(baseline, stream, ensure_ascii=False, indent=2)
            stream.write("\n")
    except FileExistsError as exception:
        raise FileExistsError(f"refusing to overwrite existing baseline: {baseline_path}") from exception
    return baseline


def validate_baseline(project_root: Path, baseline_path: Path) -> list[str]:
    baseline = json.loads(baseline_path.read_text(encoding="utf-8"))
    failures = []
    for record in baseline.get("sourceFiles", []):
        path = project_root / record["path"]
        if not path.is_file():
            failures.append(f"SOURCE_MISSING:{record['path']}")
        elif sha256_file(path) != record["sha256"]:
            failures.append(f"SOURCE_HASH_MISMATCH:{record['path']}")
    capsule = baseline.get("sourceCapsule", {})
    if capsule:
        path = project_root / capsule["path"]
        if not path.is_file() or sha256_file(path) != capsule.get("sha256"):
            failures.append("SOURCE_CAPSULE_HASH_MISMATCH")
    manifest_path = project_root / baseline.get("manifestPath", "")
    if not manifest_path.is_file() or sha256_file(manifest_path) != baseline.get("manifestSha256"):
        failures.append("MANIFEST_HASH_MISMATCH")
    old_gold_root = project_root / baseline.get("oldGoldRoot", "")
    if records_hash(tree_records(old_gold_root)) != baseline.get("oldGoldTreeSha256"):
        failures.append("OLD_GOLD_TREE_HASH_MISMATCH")
    return failures
