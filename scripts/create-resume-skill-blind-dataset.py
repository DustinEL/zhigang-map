from __future__ import annotations

import argparse
from pathlib import Path

from resume_skill_blind_intake import audit_dataset, publish_intake


PROJECT_ROOT = Path(__file__).resolve().parents[1]


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="审计并登记 30 份独立简历盲测数据集")
    parser.add_argument("--resume-dir", type=Path, required=True)
    parser.add_argument("--old-manifest", type=Path, required=True)
    parser.add_argument("--output-dir", type=Path, required=True)
    parser.add_argument("--expected-count", type=int, default=30)
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    manifest, independence, eligibility = audit_dataset(
        args.resume_dir.resolve(), args.old_manifest.resolve(), PROJECT_ROOT, args.expected_count
    )
    audit_dir = publish_intake(args.output_dir.resolve(), manifest, independence, eligibility)
    print(f"数据集 SHA-256: {manifest['datasetSha256']}")
    print(f"独立性审计: {independence['status']}")
    print(f"内容准入审计: {eligibility['status']}")
    print(f"审计产物: {audit_dir}")
    if independence["status"] != "PASS" or eligibility["status"] != "PASS":
        return 2
    print(f"正式盲测清单已发布: {args.output_dir.resolve()}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
