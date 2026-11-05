"""生成全新人岗匹配盲测的数据接收、无标签清单和双人标注模板。"""

import argparse
import csv
import hashlib
import json
from datetime import datetime, timezone
from pathlib import Path


RESUME_FIELDS = [
    "resumeId", "candidateKey", "sourcePath", "contentSha256", "normalizedTextSha256",
    "duplicateGroup", "split", "consentOrLicense", "piiRedacted", "collectedBy",
    "collectedAt", "notes",
]
CASE_FIELDS = ["caseId", "resumeId", "roleId", "roleCode", "roleName", "roleVersion"]


def sha256(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def write_csv(path, fields, rows):
    with path.open("w", encoding="utf-8-sig", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=fields)
        writer.writeheader()
        writer.writerows(rows)


def create_intake(cards_path, output, resume_count=30, role_ids=None):
    cards_path = cards_path.resolve()
    output.mkdir(parents=True, exist_ok=True)
    catalog = json.loads(cards_path.read_text(encoding="utf-8"))
    catalog_roles = catalog.get("cards", [])
    if role_ids is None:
        roles = catalog_roles
        if len(roles) != 8:
            raise ValueError("默认盲测接收包必须使用恰好 8 个冻结岗位")
    else:
        requested = [int(role_id) for role_id in role_ids]
        if not requested or len(requested) != len(set(requested)):
            raise ValueError("role_ids 必须非空且不能重复")
        by_id = {int(role["roleId"]): role for role in catalog_roles}
        missing = [role_id for role_id in requested if role_id not in by_id]
        if missing:
            raise ValueError(f"role_ids 包含未知岗位: {missing}")
        roles = [by_id[role_id] for role_id in requested]
    if resume_count < 30:
        raise ValueError("正式盲测至少需要 30 份全新简历")

    resumes = [{
        "resumeId": f"FMB-{index:03d}",
        "candidateKey": "",
        "sourcePath": "",
        "contentSha256": "",
        "normalizedTextSha256": "",
        "duplicateGroup": "",
        "split": "blind",
        "consentOrLicense": "",
        "piiRedacted": "",
        "collectedBy": "",
        "collectedAt": "",
        "notes": "",
    } for index in range(1, resume_count + 1)]

    cases = []
    annotations = []
    case_number = 1
    for resume in resumes:
        for role in roles:
            case_id = f"FMB-C-{case_number:04d}"
            cases.append({
                "caseId": case_id,
                "resumeId": resume["resumeId"],
                "roleId": role["roleId"],
                "roleCode": role["roleCode"],
                "roleName": role["roleName"],
                "roleVersion": role["version"],
            })
            annotations.append({
                "caseId": case_id,
                "resumeId": resume["resumeId"],
                "roleId": role["roleId"],
                "label": "",
                "evidence": [],
                "reasonCodes": [],
                "comments": "",
            })
            case_number += 1

    write_csv(output / "resume-registry.csv", RESUME_FIELDS, resumes)
    write_csv(output / "case-manifest.csv", CASE_FIELDS, cases)
    for filename in ("annotation-a.json", "annotation-b.json"):
        (output / filename).write_text(
            json.dumps(annotations, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

    guidelines = """# 全新人岗匹配盲测数据接收说明

## 简历接收

1. 只接收未参与旧 56/96 集、开发、验证或岗位能力卡制定的简历。
2. `candidateKey` 使用稳定匿名身份键，同一人的不同语言、版本或格式必须相同。
3. `contentSha256` 是原始文件哈希，`normalizedTextSha256` 是标准化文本哈希。
4. 疑似近重复简历填写同一个 `duplicateGroup`，不得跨数据集合。
5. 必须记录合法来源或授权，并在进入标注前完成 PII 脱敏。

## 独立标注

1. 两名标注者分别使用 `annotation-a.json` 和 `annotation-b.json`，不得查看系统预测或对方结果。
2. 标签只能是 `需先补关键能力`、`接近可申请`、`可申请实习/校招`。
3. 每条标签必须填写原文证据和原因码，不得只给结论。
4. 标注完成后使用 `validate-match-annotations.py` 检查一致率、Kappa、类别支持度和候选人隔离。
5. 分歧由第三人仲裁；最终 gold 在预测程序运行结束前不得提供给开发环境。

## 当前状态

本目录只是空白接收模板，不是已冻结数据集，也不能用于声明准确率。
"""
    (output / "collection-guidelines.md").write_text(guidelines, encoding="utf-8")

    manifest = {
        "schemaVersion": "match-final-blind-intake-v1.0",
        "status": "INTAKE_INCOMPLETE",
        "createdAt": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
        "roleCatalogPath": cards_path.as_posix(),
        "roleCatalogSchemaVersion": catalog.get("schemaVersion"),
        "roleCatalogSha256": sha256(cards_path),
        "plannedResumeCount": len(resumes),
        "plannedRoleCount": len(roles),
        "plannedCaseCount": len(cases),
        "containsLabels": False,
        "roles": [{
            "roleId": role["roleId"],
            "roleCode": role["roleCode"],
            "roleName": role["roleName"],
            "roleVersion": role["version"],
        } for role in roles],
        "resumes": resumes,
        "cases": cases,
    }
    (output / "intake-manifest.json").write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return manifest


def parse_args():
    root = Path(__file__).resolve().parents[1]
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--cards", type=Path,
                        default=root / "backend/src/main/resources/matching/role-capability-cards-v2.json")
    parser.add_argument("--output", type=Path,
                        default=root / "test-data/matching/final-blind-intake-v1")
    parser.add_argument("--resume-count", type=int, default=30)
    parser.add_argument("--role-ids",
                        help="逗号分隔的冻结岗位 ID；省略时保留完整 8 岗位兼容模式")
    return parser.parse_args()


def main():
    args = parse_args()
    role_ids = None if not args.role_ids else [
        int(value.strip()) for value in args.role_ids.split(",") if value.strip()
    ]
    manifest = create_intake(args.cards, args.output, args.resume_count, role_ids)
    print(f"盲测接收包已生成: {args.output.resolve()}")
    print(f"简历登记位: {manifest['plannedResumeCount']}")
    print(f"无标签匹配对: {manifest['plannedCaseCount']}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
