from __future__ import annotations

import argparse
import hashlib
import importlib.util
import json
import math
from collections import Counter
from pathlib import Path


DEFAULT_SEED = 20260818


def file_sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def read_jsonl(path: Path) -> list[dict]:
    return [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines() if line.strip()]


def stable_rank(record: dict, seed: int) -> str:
    value = f"{seed}:{record.get('recordId', '')}".encode("utf-8")
    return hashlib.sha256(value).hexdigest()


def allocate_role_quotas(role_counts: Counter, pilot_size: int) -> dict[str, int]:
    if pilot_size < len(role_counts):
        raise ValueError("试验集数量不足以覆盖全部主岗位方向")
    if pilot_size > sum(role_counts.values()):
        raise ValueError("试验集数量超过输入记录数")
    total = sum(role_counts.values())
    raw = {role: count * pilot_size / total for role, count in role_counts.items()}
    quotas = {role: max(1, math.floor(target)) for role, target in raw.items()}
    while sum(quotas.values()) < pilot_size:
        role = max(role_counts, key=lambda item: (raw[item] - quotas[item], role_counts[item], item))
        quotas[role] += 1
    while sum(quotas.values()) > pilot_size:
        removable = [role for role, quota in quotas.items() if quota > 1]
        if not removable:
            raise ValueError("无法在保持岗位覆盖的前提下分配名额")
        role = min(removable, key=lambda item: (raw[item] - quotas[item], -role_counts[item], item))
        quotas[role] -= 1
    return dict(sorted(quotas.items()))


def split_records(records: list[dict], pilot_size: int = 30, seed: int = DEFAULT_SEED):
    record_ids = [str(record.get("recordId") or "") for record in records]
    if any(not record_id for record_id in record_ids) or len(record_ids) != len(set(record_ids)):
        raise ValueError("recordId 缺失或不唯一")
    role_counts = Counter(str(record.get("primaryTargetRoleCode") or "") for record in records)
    if "" in role_counts:
        raise ValueError("存在缺失主岗位方向的记录")
    quotas = allocate_role_quotas(role_counts, pilot_size)
    by_role = {
        role: [record for record in records if record.get("primaryTargetRoleCode") == role]
        for role in role_counts
    }
    selected_ids: set[str] = set()
    company_counts: Counter = Counter()
    source_counts: Counter = Counter()
    remaining = dict(quotas)
    while sum(remaining.values()):
        role = min((item for item in remaining if remaining[item] > 0), key=lambda item: (-remaining[item], item))
        candidates = [record for record in by_role[role] if record["recordId"] not in selected_ids]
        if not candidates:
            raise ValueError(f"岗位方向 {role} 没有足够记录")

        def diversity_score(record: dict):
            company = str(record.get("companyName") or record.get("company") or "")
            source = str(record.get("sourcePlatform") or record.get("sourceName") or "")
            return (
                company_counts[company] > 0,
                company_counts[company],
                source_counts[source] > 0,
                source_counts[source],
                stable_rank(record, seed),
            )

        chosen = min(candidates, key=diversity_score)
        selected_ids.add(chosen["recordId"])
        company_counts[str(chosen.get("companyName") or chosen.get("company") or "")] += 1
        source_counts[str(chosen.get("sourcePlatform") or chosen.get("sourceName") or "")] += 1
        remaining[role] -= 1

    pilot = [record for record in records if record["recordId"] in selected_ids]
    holdout = [record for record in records if record["recordId"] not in selected_ids]
    pilot_ids = {record["recordId"] for record in pilot}
    holdout_ids = {record["recordId"] for record in holdout}
    summary = {
        "seed": seed,
        "pilotCount": len(pilot),
        "holdoutCount": len(holdout),
        "intersectionCount": len(pilot_ids & holdout_ids),
        "unionCount": len(pilot_ids | holdout_ids),
        "pilotByPrimaryTargetRole": dict(sorted(Counter(
            record["primaryTargetRoleCode"] for record in pilot
        ).items())),
        "holdoutByPrimaryTargetRole": dict(sorted(Counter(
            record["primaryTargetRoleCode"] for record in holdout
        ).items())),
        "pilotCompanyCount": len({record.get("companyName") or record.get("company") for record in pilot}),
        "pilotSourceCount": len({record.get("sourcePlatform") or record.get("sourceName") for record in pilot}),
    }
    return pilot, holdout, summary


def manifest_record(record: dict) -> dict:
    return {
        "recordId": record.get("recordId"),
        "candidateRecordId": record.get("candidateRecordId"),
        "sourceName": record.get("sourceName"),
        "sourceType": record.get("sourceType"),
        "sourceUrl": record.get("sourceUrl"),
        "companyName": record.get("companyName") or record.get("company"),
        "jobTitle": record.get("jobTitle"),
        "city": record.get("city"),
        "level": record.get("level"),
        "primaryTargetRoleCode": record.get("primaryTargetRoleCode"),
        "secondaryTargetRoleCodes": record.get("secondaryTargetRoleCodes") or [],
        "contentSha256": record.get("contentSha256"),
    }


def build_manifest(name: str, records: list[dict], data_sha256: str, parent_sha256: str) -> dict:
    count = len(records)
    return {
        "schemaVersion": "1.1.0",
        "dataset": name,
        "recordCount": count,
        "sampleCount": count,
        "dataFileSha256": data_sha256,
        "parentDatasetSha256": parent_sha256,
        "byPrimaryTargetRole": dict(sorted(Counter(
            record["primaryTargetRoleCode"] for record in records
        ).items())),
        "bySourcePlatform": dict(sorted(Counter(
            str(record.get("sourcePlatform") or record.get("sourceName") or "") for record in records
        ).items())),
        "records": [manifest_record(record) for record in records],
    }


def write_jsonl(path: Path, records: list[dict]) -> None:
    path.write_text(
        "".join(json.dumps(record, ensure_ascii=False) + "\n" for record in records),
        encoding="utf-8",
    )


def load_annotation_generator():
    path = Path(__file__).with_name("generate-jd-annotation-package.py")
    spec = importlib.util.spec_from_file_location("generate_jd_annotation_package", path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def validate_inputs(records: list[dict], manifest: dict) -> None:
    manifest_records = manifest.get("records") or []
    manifest_by_id = {record.get("recordId"): record for record in manifest_records}
    if manifest.get("recordCount") != len(records) or len(manifest_by_id) != len(records):
        raise ValueError("原始 manifest 数量与数据不一致")
    for record in records:
        raw_hash = hashlib.sha256(str(record.get("rawText") or "").encode("utf-8")).hexdigest()
        manifest_record_value = manifest_by_id.get(record.get("recordId"))
        if raw_hash != record.get("contentSha256"):
            raise ValueError(f"正文哈希不一致：{record.get('recordId')}")
        if not manifest_record_value or manifest_record_value.get("contentSha256") != raw_hash:
            raise ValueError(f"manifest 未绑定正文：{record.get('recordId')}")


def write_outputs(data_path: Path, manifest_path: Path, output_dir: Path,
                  pilot_size: int = 30, seed: int = DEFAULT_SEED) -> dict:
    records = read_jsonl(data_path)
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    validate_inputs(records, manifest)
    pilot, holdout, summary = split_records(records, pilot_size, seed)
    output_dir.mkdir(parents=True, exist_ok=True)
    annotation_dir = output_dir / "annotation"
    annotation_dir.mkdir(parents=True, exist_ok=True)

    pilot_data_path = output_dir / f"pilot-{len(pilot)}.jsonl"
    holdout_data_path = output_dir / f"holdout-{len(holdout)}.jsonl"
    pilot_manifest_path = output_dir / f"pilot-{len(pilot)}-manifest.json"
    holdout_manifest_path = output_dir / f"holdout-{len(holdout)}-manifest.json"
    write_jsonl(pilot_data_path, pilot)
    write_jsonl(holdout_data_path, holdout)
    parent_sha256 = file_sha256(data_path)
    pilot_manifest = build_manifest(
        f"jd-pilot-{len(pilot)}", pilot, file_sha256(pilot_data_path), parent_sha256)
    holdout_manifest = build_manifest(
        f"jd-holdout-{len(holdout)}", holdout, file_sha256(holdout_data_path), parent_sha256)
    pilot_manifest_path.write_text(json.dumps(pilot_manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    holdout_manifest_path.write_text(json.dumps(holdout_manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

    annotation_path = annotation_dir / "annotator.xlsx"
    load_annotation_generator().create_annotation_workbook(pilot, "试验集", annotation_path)
    summary.update({
        "schemaVersion": "1.0.0",
        "inputDataSha256": parent_sha256,
        "inputManifestSha256": file_sha256(manifest_path),
        "pilotDataSha256": file_sha256(pilot_data_path),
        "pilotManifestSha256": file_sha256(pilot_manifest_path),
        "holdoutDataSha256": file_sha256(holdout_data_path),
        "holdoutManifestSha256": file_sha256(holdout_manifest_path),
        "annotationWorkbookSha256": file_sha256(annotation_path),
    })
    (output_dir / "split-summary.json").write_text(
        json.dumps(summary, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return summary


def main() -> int:
    parser = argparse.ArgumentParser(description="将冻结 JD 拆分为试验集和保留盲测集")
    parser.add_argument("--data", type=Path, required=True)
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--pilot-size", type=int, default=30)
    parser.add_argument("--seed", type=int, default=DEFAULT_SEED)
    args = parser.parse_args()
    result = write_outputs(args.data, args.manifest, args.output, args.pilot_size, args.seed)
    print(json.dumps(result, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
