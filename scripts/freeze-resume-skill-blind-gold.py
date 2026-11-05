from __future__ import annotations

import argparse
import json
from pathlib import Path

from openpyxl import load_workbook

from resume_skill_blind_baseline import validate_baseline
from resume_skill_blind_freeze import build_gold, freeze_gold


PROJECT_ROOT = Path(__file__).resolve().parents[1]


def read_json(path: Path) -> dict:
    return json.loads(path.read_text(encoding="utf-8"))


def main() -> int:
    parser = argparse.ArgumentParser(description="逐样本验证并冻结独立盲测金标")
    parser.add_argument("--data-dir", type=Path, required=True)
    parser.add_argument("--baseline", type=Path, required=True)
    parser.add_argument("--validate-only", action="store_true")
    args = parser.parse_args()

    data_dir = args.data_dir.resolve()
    manifest = read_json(data_dir / "resume-skill-blind-30-manifest.json")
    independence = read_json(data_dir / "resume-skill-blind-30-independence-audit.json")
    eligibility = read_json(data_dir / "resume-skill-blind-30-eligibility-review.json")
    baseline = read_json(args.baseline.resolve())
    if manifest.get("sampleCount") != 30:
        raise ValueError(f"盲测样本数必须为 30，实际为 {manifest.get('sampleCount')}")
    if independence.get("status") != "PASS" or eligibility.get("status") != "PASS":
        raise ValueError("准入或独立性审计未通过")
    if baseline.get("datasetSha256") != manifest.get("datasetSha256"):
        raise ValueError("baseline 与 manifest 的数据集 SHA-256 不一致")
    baseline_failures = validate_baseline(PROJECT_ROOT, args.baseline.resolve())
    if baseline_failures:
        raise ValueError(f"baseline 完整性校验失败: {baseline_failures}")

    workbook = load_workbook(data_dir / "resume-skill-blind-30-annotation.xlsx", data_only=False)
    gold = build_gold(workbook, manifest, baseline["sourceTreeSha256"])
    if args.validate_only:
        print(f"验证通过: {gold['sampleCount']} 份样本；不会写入 gold")
        return 0
    output = data_dir / "resume-skill-blind-30-gold-v1.json"
    freeze_gold(workbook, manifest, baseline["sourceTreeSha256"], output)
    print(f"已冻结金标: {output}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
