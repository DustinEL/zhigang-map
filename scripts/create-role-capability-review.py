"""从版本化岗位能力卡生成双人独立审核包。"""

import argparse
import csv
import hashlib
import json
from datetime import datetime, timezone
from pathlib import Path


REVIEW_FIELDS = [
    "roleId", "roleCode", "roleName", "cardVersion", "itemType", "capabilityId",
    "currentValues", "nearEvidenceLevel", "readyEvidenceLevel", "blocking",
    "sourceReferences", "decision", "proposedValues", "proposedNearEvidenceLevel",
    "proposedReadyEvidenceLevel", "proposedBlocking", "evidenceOrReason", "comments",
    "reviewerId", "reviewedAt",
]


def sha256(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def review_rows(cards):
    rows = []
    for card in cards:
        common = {
            "roleId": card["roleId"],
            "roleCode": card["roleCode"],
            "roleName": card["roleName"],
            "cardVersion": card["version"],
            "sourceReferences": " | ".join(card.get("sourceReferences", [])),
            "decision": "",
            "proposedValues": "",
            "proposedNearEvidenceLevel": "",
            "proposedReadyEvidenceLevel": "",
            "proposedBlocking": "",
            "evidenceOrReason": "",
            "comments": "",
            "reviewerId": "",
            "reviewedAt": "",
        }
        for capability in card.get("coreCapabilities", []):
            rows.append({
                **common,
                "itemType": "CORE",
                "capabilityId": capability["id"],
                "currentValues": " | ".join(capability.get("alternatives", [])),
                "nearEvidenceLevel": capability["nearEvidenceLevel"],
                "readyEvidenceLevel": capability["readyEvidenceLevel"],
                "blocking": str(bool(capability["blocking"])).lower(),
            })
        for item_type, field in (("TRANSFERABLE", "transferableSkills"), ("BONUS", "bonusSkills")):
            rows.append({
                **common,
                "itemType": item_type,
                "capabilityId": field,
                "currentValues": " | ".join(card.get(field, [])),
                "nearEvidenceLevel": "",
                "readyEvidenceLevel": "",
                "blocking": "",
            })
    return rows


def write_csv(path, fields, rows):
    with path.open("w", encoding="utf-8-sig", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=fields)
        writer.writeheader()
        writer.writerows(rows)


def create_review_package(source, output):
    source = source.resolve()
    output.mkdir(parents=True, exist_ok=True)
    data = json.loads(source.read_text(encoding="utf-8"))
    cards = data.get("cards", [])
    if len(cards) != 8 and source.name == "role-capability-cards-v2.json":
        raise ValueError("正式能力卡必须恰好包含 8 个岗位")
    rows = review_rows(cards)
    core_count = sum(row["itemType"] == "CORE" for row in rows)

    write_csv(output / "reviewer-a.csv", REVIEW_FIELDS, rows)
    write_csv(output / "reviewer-b.csv", REVIEW_FIELDS, rows)

    adjudication_fields = [
        "roleId", "roleCode", "roleName", "cardVersion", "itemType", "capabilityId",
        "currentValues", "reviewerADecision", "reviewerBDecision", "agreement",
        "finalDecision", "finalValues", "finalNearEvidenceLevel", "finalReadyEvidenceLevel",
        "finalBlocking", "adjudicationReason", "adjudicatorId", "adjudicatedAt",
    ]
    adjudication_rows = [{field: row.get(field, "") for field in adjudication_fields} for row in rows]
    write_csv(output / "adjudication.csv", adjudication_fields, adjudication_rows)

    guidelines = """# 岗位能力卡独立审核说明

## 审核原则

1. 两名审核人分别填写 `reviewer-a.csv` 和 `reviewer-b.csv`，审核期间不得互看结果。
2. `decision` 只能填写 `ACCEPT`、`MODIFY` 或 `REJECT`。
3. `CORE` 行需逐项检查技能是否为规范化短名称、证据等级是否合理、是否属于阻断能力。
4. 选择 `MODIFY` 时必须填写建议值和依据；选择 `REJECT` 时必须填写原因。
5. `TRANSFERABLE` 和 `BONUS` 不得抵消阻断核心能力缺口。
6. `reviewerId` 必须为不同人员，`reviewedAt` 使用 ISO-8601 时间。
7. 两份审核完成后才填写 `adjudication.csv`。存在分歧的行必须由第三人裁决。

## 证据等级

- L0：无有效能力证据或明确否定。
- L1：课程、学习或仅技能声明。
- L2：同一项目中存在明确使用行为。
- L3：存在可核查的任务结果、测试结果或量化产出。
- L4：实习/生产环境中同时具备行为、对象和结果证据。

## 冻结条件

- 8 个岗位全部完成审核；
- 每个岗位保持 3 至 5 个核心能力组；
- 不存在整句 JD 作为技能；
- 两名审核人身份不同；
- 所有分歧均有第三方仲裁记录；
- 冻结文件记录源能力卡和审核文件 SHA-256。
"""
    (output / "review-guidelines.md").write_text(guidelines, encoding="utf-8")

    manifest = {
        "schemaVersion": "role-capability-review-v1.0",
        "status": "DRAFT_REVIEW_PACKAGE",
        "createdAt": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
        "sourcePath": source.as_posix(),
        "sourceSchemaVersion": data.get("schemaVersion"),
        "sourceSha256": sha256(source),
        "roleCount": len(cards),
        "coreCapabilityCount": core_count,
        "requiredReviewers": 2,
        "requiredAdjudicators": 1,
        "reviewFiles": ["reviewer-a.csv", "reviewer-b.csv", "adjudication.csv"],
    }
    (output / "manifest.json").write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return manifest


def parse_args():
    root = Path(__file__).resolve().parents[1]
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", type=Path,
                        default=root / "backend/src/main/resources/matching/role-capability-cards-v2.json")
    parser.add_argument("--output", type=Path,
                        default=root / "test-data/matching/role-capability-review-v2")
    return parser.parse_args()


def main():
    args = parse_args()
    manifest = create_review_package(args.source, args.output)
    print(f"审核包已生成: {args.output.resolve()}")
    print(f"岗位数: {manifest['roleCount']}")
    print(f"核心能力组数: {manifest['coreCapabilityCount']}")
    print(f"源文件 SHA-256: {manifest['sourceSha256']}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
