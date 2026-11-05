import argparse
import hashlib
import json
import sys
from datetime import datetime, timezone
from pathlib import Path


EXPECTED_DATASET_ID = "match-blind-96-v1"
EXPECTED_ROLES = (
    (2, "Java 开发工程师"),
    (4, "AI 应用后端工程师"),
    (3, "AI Agent 开发工程师"),
    (5, "LLMOps / 模型服务工程师"),
    (7, "大模型应用开发工程师"),
    (9, "AI 数据工程师"),
    (12, "AI 安全测试工程师"),
    (13, "大模型推理加速工程师"),
)
VALID_LABELS = {
    "可申请实习/校招",
    "接近可申请",
    "需先补关键能力",
    "无法判断",
}
UNDECIDABLE_LABEL = "无法判断"
ANNOTATION_HEADER = (
    "Case ID",
    "简历编号",
    "简历",
    "岗位编号",
    "岗位",
    "人工标签",
    "判断依据",
    "备注",
)


class ValidationError(ValueError):
    pass


def sha256_bytes(content):
    return hashlib.sha256(content).hexdigest()


def canonical_sha256(value):
    canonical = json.dumps(
        value,
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
    ).encode("utf-8")
    return sha256_bytes(canonical)


def load_and_validate_manifest(path):
    raw = path.read_bytes()
    try:
        manifest = json.loads(raw.decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError) as error:
        raise ValidationError(f"manifest 不是有效 UTF-8 JSON: {error}") from error

    if manifest.get("datasetId") != EXPECTED_DATASET_ID:
        raise ValidationError(f"manifest datasetId 必须为 {EXPECTED_DATASET_ID}")

    stored_dataset_hash = manifest.get("datasetSha256")
    unhashed_manifest = dict(manifest)
    unhashed_manifest.pop("datasetSha256", None)
    expected_dataset_hash = canonical_sha256(unhashed_manifest)
    if stored_dataset_hash != expected_dataset_hash:
        raise ValidationError("manifest datasetSha256 校验失败")

    resumes = manifest.get("resumes")
    roles = manifest.get("roles")
    cases = manifest.get("cases")
    if not isinstance(resumes, list) or len(resumes) != 12:
        raise ValidationError("manifest 必须包含恰好 12 份简历")
    if not isinstance(roles, list) or len(roles) != 8:
        raise ValidationError("manifest 必须包含恰好 8 个岗位")
    if not isinstance(cases, list) or len(cases) != 96:
        raise ValidationError("manifest 必须包含恰好 96 个 cases")

    resume_ids = [str(resume.get("resumeId", "")).strip() for resume in resumes]
    if any(not resume_id for resume_id in resume_ids) or len(set(resume_ids)) != 12:
        raise ValidationError("manifest 简历编号必须是 12 个非空唯一值")

    actual_roles = tuple(
        (role.get("roleId"), str(role.get("roleName", "")).strip()) for role in roles
    )
    if actual_roles != EXPECTED_ROLES:
        raise ValidationError("manifest 固定岗位 ID、名称或顺序不一致")

    case_ids = []
    combinations = []
    valid_resume_ids = set(resume_ids)
    valid_role_ids = {role_id for role_id, _ in EXPECTED_ROLES}
    for case in cases:
        case_id = str(case.get("caseId", "")).strip()
        resume_id = str(case.get("resumeId", "")).strip()
        role_id = case.get("roleId")
        if not case_id:
            raise ValidationError("manifest caseId 不得为空")
        if resume_id not in valid_resume_ids or role_id not in valid_role_ids:
            raise ValidationError(f"manifest case {case_id} 引用了未知简历或岗位")
        case_ids.append(case_id)
        combinations.append((resume_id, role_id))

    if len(set(case_ids)) != 96:
        raise ValidationError("manifest 的 96 个 caseId 必须唯一")
    expected_combinations = {
        (resume_id, role_id)
        for resume_id in resume_ids
        for role_id, _ in EXPECTED_ROLES
    }
    if set(combinations) != expected_combinations or len(set(combinations)) != 96:
        raise ValidationError("manifest cases 必须完整覆盖 12×8 且不得重复")

    return manifest, sha256_bytes(raw)


def parse_markdown_row(line):
    stripped = line.strip()
    if not stripped.startswith("|") or not stripped.endswith("|"):
        return None
    return [cell.strip() for cell in stripped[1:-1].split("|")]


def parse_annotation(path):
    raw = path.read_bytes()
    try:
        lines = raw.decode("utf-8").splitlines()
    except UnicodeDecodeError as error:
        raise ValidationError(f"annotation 不是有效 UTF-8 Markdown: {error}") from error

    header_index = None
    for index, line in enumerate(lines):
        cells = parse_markdown_row(line)
        if cells and tuple(cells) == ANNOTATION_HEADER:
            header_index = index
            break
    if header_index is None:
        raise ValidationError("annotation 缺少预期的 8 列表头")

    rows = []
    for line in lines[header_index + 2 :]:
        cells = parse_markdown_row(line)
        if cells is None or all(not cell for cell in cells):
            continue
        if len(cells) != len(ANNOTATION_HEADER):
            raise ValidationError("annotation 数据行必须恰好包含 8 列")
        rows.append(
            {
                "caseId": cells[0],
                "resumeId": cells[1],
                "resumeName": cells[2],
                "roleId": cells[3],
                "roleName": cells[4],
                "label": cells[5],
                "evidence": cells[6],
                "notes": cells[7],
            }
        )
    return rows, sha256_bytes(raw)


def validate_annotation(manifest, rows):
    seen_case_ids = set()
    for row in rows:
        case_id = row["caseId"]
        if case_id in seen_case_ids:
            raise ValidationError(f"annotation 存在重复 caseId: {case_id}")
        seen_case_ids.add(case_id)

    manifest_cases = {case["caseId"]: case for case in manifest["cases"]}
    if set(seen_case_ids) != set(manifest_cases):
        missing = sorted(set(manifest_cases) - seen_case_ids)
        extra = sorted(seen_case_ids - set(manifest_cases))
        raise ValidationError(
            f"annotation 与 manifest 清单不一致: 缺少 {missing}，多出 {extra}"
        )

    resume_names = {
        str(resume["resumeId"]): str(resume["resumeName"]).strip()
        for resume in manifest["resumes"]
    }
    role_names = {
        role["roleId"]: str(role["roleName"]).strip() for role in manifest["roles"]
    }
    normalized = []
    invalid_labels = []
    unlabeled = []
    for row in rows:
        case = manifest_cases[row["caseId"]]
        try:
            role_id = int(row["roleId"])
        except ValueError as error:
            raise ValidationError(
                f"annotation 与 manifest 清单不一致: {row['caseId']} 岗位编号无效"
            ) from error
        expected_identity = (
            str(case["resumeId"]),
            resume_names[str(case["resumeId"])],
            case["roleId"],
            role_names[case["roleId"]],
        )
        actual_identity = (
            row["resumeId"],
            row["resumeName"],
            role_id,
            row["roleName"],
        )
        if actual_identity != expected_identity:
            raise ValidationError(
                f"annotation 与 manifest 清单不一致: {row['caseId']} 身份字段被修改"
            )
        if not row["label"]:
            unlabeled.append(row["caseId"])
        elif row["label"] not in VALID_LABELS:
            invalid_labels.append(f"{row['caseId']}={row['label']}")
        normalized.append(
            {
                "caseId": row["caseId"],
                "resumeId": row["resumeId"],
                "resumeName": row["resumeName"],
                "roleId": role_id,
                "roleName": row["roleName"],
                "label": row["label"],
                "evidence": row["evidence"],
                "notes": row["notes"],
            }
        )

    if invalid_labels:
        raise ValidationError("annotation 存在非法标签: " + ", ".join(invalid_labels))
    return normalized, unlabeled


def build_gold(manifest, manifest_hash, annotation_hash, reviewer, cases):
    gold = {
        "schemaVersion": 1,
        "datasetId": manifest["datasetId"],
        "datasetSha256": manifest["datasetSha256"],
        "manifestSha256": manifest_hash,
        "annotationSha256": annotation_hash,
        "reviewer": reviewer,
        "frozenAt": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
        "validCases": [case for case in cases if case["label"] != UNDECIDABLE_LABEL],
        "undecidableCases": [case for case in cases if case["label"] == UNDECIDABLE_LABEL],
    }
    gold["goldSha256"] = canonical_sha256(gold)
    return gold


def parse_args():
    project_root = Path(__file__).resolve().parents[1]
    parser = argparse.ArgumentParser(description="校验并冻结独立人岗匹配盲测人工标签")
    parser.add_argument(
        "--manifest",
        type=Path,
        default=project_root / "test-data/evaluation/match-blind-96-manifest.json",
    )
    parser.add_argument(
        "--annotation",
        type=Path,
        default=project_root / "test-data/evaluation/match-blind-96-annotation.md",
    )
    parser.add_argument(
        "--gold",
        type=Path,
        default=project_root / "test-data/evaluation/match-blind-96-gold.json",
    )
    parser.add_argument("--reviewer", default="")
    parser.add_argument("--validate-only", action="store_true")
    return parser.parse_args()


def run(args):
    manifest, manifest_hash = load_and_validate_manifest(args.manifest.resolve())
    rows, annotation_hash = parse_annotation(args.annotation.resolve())
    cases, unlabeled = validate_annotation(manifest, rows)
    reviewer = args.reviewer.strip()
    if not reviewer:
        raise ValidationError("校验和冻结必须填写非空审核人 --reviewer")

    if args.validate_only:
        print(f"未标注: {len(unlabeled)}")
        return 2 if unlabeled else 0

    if unlabeled:
        raise ValidationError(
            f"人工标签未完成，仍有 {len(unlabeled)} 条未标注: {', '.join(unlabeled)}"
        )

    gold = build_gold(manifest, manifest_hash, annotation_hash, reviewer, cases)
    args.gold.parent.mkdir(parents=True, exist_ok=True)
    args.gold.write_text(
        json.dumps(gold, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )
    print(f"冻结完成: {args.gold.resolve()}")
    print(f"有效样本: {len(gold['validCases'])}")
    print(f"无法判断: {len(gold['undecidableCases'])}")
    print(f"goldSha256: {gold['goldSha256']}")
    return 0


def main():
    try:
        return run(parse_args())
    except (OSError, ValidationError) as error:
        print(f"错误: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
