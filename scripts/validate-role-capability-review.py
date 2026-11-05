"""校验岗位能力卡双人独立审核是否达到冻结条件。"""

import argparse
import csv
import hashlib
import json
import sys
from pathlib import Path


VALID_DECISIONS = {"ACCEPT", "MODIFY", "REJECT"}
IDENTITY_FIELDS = ("roleId", "roleCode", "cardVersion", "itemType", "capabilityId")
DECISION_FIELDS = (
    "decision", "proposedValues", "proposedNearEvidenceLevel",
    "proposedReadyEvidenceLevel", "proposedBlocking",
)


def sha256(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def read_csv(path):
    with path.open(encoding="utf-8-sig", newline="") as handle:
        return list(csv.DictReader(handle))


def row_key(row):
    return tuple(row.get(field, "").strip() for field in IDENTITY_FIELDS)


def decision_key(row):
    return tuple(row.get(field, "").strip() for field in DECISION_FIELDS)


def reviewer_id(rows):
    values = {row.get("reviewerId", "").strip() for row in rows if row.get("reviewerId", "").strip()}
    return next(iter(values)) if len(values) == 1 else ""


def validate_review_package(package, source):
    package = package.resolve()
    source = source.resolve()
    manifest = json.loads((package / "manifest.json").read_text(encoding="utf-8"))
    rows_a = read_csv(package / "reviewer-a.csv")
    rows_b = read_csv(package / "reviewer-b.csv")
    adjudication = {row_key(row): row for row in read_csv(package / "adjudication.csv")}
    failures = []

    if manifest.get("sourceSha256") != sha256(source):
        failures.append("SOURCE_HASH_CHANGED")
    expected_count = manifest.get("coreCapabilityCount", 0) + manifest.get("roleCount", 0) * 2
    if len(rows_a) != expected_count or len(rows_b) != expected_count:
        failures.append("ROW_COUNT_MISMATCH")
    keys_a = [row_key(row) for row in rows_a]
    keys_b = [row_key(row) for row in rows_b]
    if len(set(keys_a)) != len(keys_a) or keys_a != keys_b:
        failures.append("REVIEW_ROW_IDENTITY_MISMATCH")

    incomplete = any(
        row.get("decision", "").strip() not in VALID_DECISIONS
        or not row.get("evidenceOrReason", "").strip()
        or not row.get("reviewerId", "").strip()
        or not row.get("reviewedAt", "").strip()
        for row in rows_a + rows_b
    )
    if incomplete:
        failures.append("INCOMPLETE_REVIEW")

    reviewer_a = reviewer_id(rows_a)
    reviewer_b = reviewer_id(rows_b)
    if not reviewer_a or not reviewer_b or reviewer_a == reviewer_b:
        failures.append("REVIEWERS_NOT_INDEPENDENT")

    agreements = 0
    unresolved = 0
    for left, right in zip(rows_a, rows_b):
        if (left.get("decision", "").strip() in VALID_DECISIONS
                and right.get("decision", "").strip() in VALID_DECISIONS
                and decision_key(left) == decision_key(right)):
            agreements += 1
            continue
        if (left.get("decision", "").strip() not in VALID_DECISIONS
                or right.get("decision", "").strip() not in VALID_DECISIONS):
            continue
        resolution = adjudication.get(row_key(left), {})
        adjudicator = resolution.get("adjudicatorId", "").strip()
        if (resolution.get("finalDecision", "").strip() not in VALID_DECISIONS
                or not resolution.get("adjudicationReason", "").strip()
                or not adjudicator or adjudicator in {reviewer_a, reviewer_b}
                or not resolution.get("adjudicatedAt", "").strip()):
            unresolved += 1
    if unresolved:
        failures.append("UNRESOLVED_DISAGREEMENT")

    total = len(rows_a)
    return {
        "passed": not failures,
        "status": "READY_TO_FREEZE" if not failures else "REVIEW_INCOMPLETE",
        "sourceSha256": manifest.get("sourceSha256"),
        "roleCount": manifest.get("roleCount"),
        "coreCapabilityCount": manifest.get("coreCapabilityCount"),
        "reviewRowCount": total,
        "agreementCount": agreements,
        "agreementRate": agreements / total if total else 0,
        "unresolvedDisagreementCount": unresolved,
        "reviewerIds": sorted(value for value in (reviewer_a, reviewer_b) if value),
        "failures": list(dict.fromkeys(failures)),
        "fileHashes": {
            "reviewerASha256": sha256(package / "reviewer-a.csv"),
            "reviewerBSha256": sha256(package / "reviewer-b.csv"),
            "adjudicationSha256": sha256(package / "adjudication.csv"),
        },
    }


def parse_args():
    root = Path(__file__).resolve().parents[1]
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--package", type=Path,
                        default=root / "test-data/matching/role-capability-review-v2")
    parser.add_argument("--source", type=Path,
                        default=root / "backend/src/main/resources/matching/role-capability-cards-v2.json")
    parser.add_argument("--report", type=Path)
    return parser.parse_args()


def main():
    args = parse_args()
    report = validate_review_package(args.package, args.source)
    output = args.report or args.package / "validation-report.json"
    output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"审核状态: {report['status']}")
    print(f"一致率: {report['agreementRate']:.2%}")
    if report["failures"]:
        print("未通过项: " + ", ".join(report["failures"]), file=sys.stderr)
    return 0 if report["passed"] else 2


if __name__ == "__main__":
    raise SystemExit(main())
