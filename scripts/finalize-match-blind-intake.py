"""校验全新简历登记表并生成不可变的人岗盲测执行清单。"""

import argparse
import csv
import hashlib
import html
import json
import re
import sys
import zipfile
from datetime import datetime, timezone
from pathlib import Path


class FinalizationError(ValueError):
    pass


def file_sha256(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def normalized_text_sha256(text):
    normalized = re.sub(r"[^0-9a-z\u4e00-\u9fff]+", "", text.casefold())
    return hashlib.sha256(normalized.encode("utf-8")).hexdigest()


def extract_text(path):
    suffix = path.suffix.casefold()
    if suffix in {".txt", ".md"}:
        return path.read_text(encoding="utf-8-sig")
    if suffix == ".docx":
        try:
            with zipfile.ZipFile(path) as archive:
                xml = archive.read("word/document.xml").decode("utf-8")
        except (KeyError, zipfile.BadZipFile, UnicodeDecodeError) as error:
            raise FinalizationError(f"无法读取 DOCX 正文: {path}") from error
        xml = re.sub(r"</w:p>", "\n", xml)
        return html.unescape(re.sub(r"<[^>]+>", "", xml))
    if suffix == ".pdf":
        try:
            from pypdf import PdfReader
            return "\n".join(page.extract_text() or "" for page in PdfReader(path).pages)
        except (ImportError, OSError, ValueError) as error:
            raise FinalizationError(f"无法读取 PDF 正文: {path}") from error
    raise FinalizationError(f"不支持的简历格式: {path.suffix}")


def read_csv(path):
    with path.open("r", encoding="utf-8-sig", newline="") as handle:
        return list(csv.DictReader(handle))


def canonical_sha256(value):
    content = json.dumps(value, ensure_ascii=False, sort_keys=True,
                         separators=(",", ":")).encode("utf-8")
    return hashlib.sha256(content).hexdigest()


def _required(row, fields, source):
    missing = [field for field in fields if not str(row.get(field, "")).strip()]
    if missing:
        raise FinalizationError(f"{source} 缺少必填字段: {missing}")


def finalize_intake(intake, root):
    intake = Path(intake).resolve()
    root = Path(root).resolve()
    if not root.is_dir():
        raise FinalizationError(f"workspace root does not exist: {root}")
    base = json.loads((intake / "intake-manifest.json").read_text(encoding="utf-8"))
    if base.get("containsLabels") is not False:
        raise FinalizationError("接收清单不得包含标签")
    registry = read_csv(intake / "resume-registry.csv")
    cases = read_csv(intake / "case-manifest.csv")
    if len(registry) != int(base.get("plannedResumeCount", -1)):
        raise FinalizationError("简历登记数量与 intake manifest 不一致")
    if len(cases) != int(base.get("plannedCaseCount", -1)):
        raise FinalizationError("case 数量与 intake manifest 不一致")

    resume_ids = set()
    candidate_keys = set()
    content_hashes = set()
    normalized_hashes = set()
    sealed_resumes = []
    required = ("resumeId", "candidateKey", "sourcePath", "contentSha256",
                "normalizedTextSha256", "duplicateGroup", "split", "consentOrLicense",
                "piiRedacted", "collectedBy", "collectedAt")
    for row in registry:
        resume_id = str(row.get("resumeId", "")).strip()
        _required(row, required, f"简历 {resume_id or '<empty>'}")
        candidate_key = row["candidateKey"].strip()
        if resume_id in resume_ids or candidate_key in candidate_keys:
            raise FinalizationError("resumeId 与 candidateKey 必须分别唯一")
        if row["split"].strip() != "blind" or row["piiRedacted"].strip().casefold() != "true":
            raise FinalizationError(f"简历 {resume_id} 必须属于 blind 且完成 PII 脱敏")
        source_value = Path(row["sourcePath"].strip())
        if source_value.is_absolute():
            raise FinalizationError(f"简历 {resume_id} sourcePath 必须是相对路径")
        source = (root / source_value).resolve()
        if not source.is_relative_to(root):
            raise FinalizationError(f"简历 {resume_id} sourcePath 逃逸根目录")
        if not source.is_file():
            raise FinalizationError(f"简历文件不存在: {source}")
        actual_content = file_sha256(source)
        if actual_content.casefold() != row["contentSha256"].strip().casefold():
            raise FinalizationError(f"简历 {resume_id} contentSha256 不一致")
        text = extract_text(source)
        if not text.strip():
            raise FinalizationError(f"简历 {resume_id} 未提取到正文")
        actual_normalized = normalized_text_sha256(text)
        if actual_normalized.casefold() != row["normalizedTextSha256"].strip().casefold():
            raise FinalizationError(f"简历 {resume_id} normalizedTextSha256 不一致")
        if actual_content in content_hashes or actual_normalized in normalized_hashes:
            raise FinalizationError("正式集合内部存在重复简历正文")
        resume_ids.add(resume_id)
        candidate_keys.add(candidate_key)
        content_hashes.add(actual_content)
        normalized_hashes.add(actual_normalized)
        sealed_resumes.append({
            **row,
            "sourcePath": source_value.as_posix(),
            "contentSha256": actual_content,
            "normalizedTextSha256": actual_normalized,
            "bytes": source.stat().st_size,
        })

    audit_path = intake / "leakage-audit.json"
    if not audit_path.is_file():
        raise FinalizationError("required leakage audit report is missing")
    try:
        leakage_report = json.loads(audit_path.read_text(encoding="utf-8"))
    except (OSError, UnicodeError, json.JSONDecodeError) as error:
        raise FinalizationError("cannot read leakage audit report") from error
    if any(str(item.get("type", "")).startswith("INTERNAL_")
           for item in leakage_report.get("findings", [])):
        raise FinalizationError("leakage audit contains internal duplicate findings")
    fingerprint_rows = sorted(({
        "resumeId": item["resumeId"],
        "candidateKey": item["candidateKey"],
        "contentSha256": item["contentSha256"],
        "normalizedTextSha256": item["normalizedTextSha256"],
    } for item in sealed_resumes), key=lambda item: (
        item["resumeId"], item["candidateKey"]))
    expected_fingerprint = canonical_sha256(fingerprint_rows)
    if leakage_report.get("schemaVersion") != "match-blind-leakage-audit-v1.0" \
            or leakage_report.get("status") != "PASS" \
            or leakage_report.get("findings") != []:
        raise FinalizationError("leakage audit must be a clean PASS")
    if leakage_report.get("intakeFingerprint") != expected_fingerprint:
        raise FinalizationError("leakage audit fingerprint does not match intake")
    history_roots = leakage_report.get("historyRoots")
    counts = leakage_report.get("counts") or {}
    if not isinstance(history_roots, list) or not history_roots \
            or counts.get("intakeResumes") != len(sealed_resumes):
        raise FinalizationError("leakage audit coverage is incomplete")
    if leakage_report.get("threshold") != 0.90:
        raise FinalizationError("leakage audit threshold must be exactly 0.90")
    audit_program = Path(__file__).with_name("audit-match-blind-leakage.py").resolve()
    expected_audit_program_sha = file_sha256(audit_program)
    if leakage_report.get("auditProgramSha256", "").casefold() != expected_audit_program_sha:
        raise FinalizationError("audit program hash is missing or does not match")
    history_file_count = counts.get("historyFiles")
    if not isinstance(history_file_count, int) or history_file_count <= 0:
        raise FinalizationError("leakage audit must cover at least one history file")
    internal_finding_count = counts.get("internalFindingCount")
    if not isinstance(internal_finding_count, int) or internal_finding_count != 0 \
            or any(str(item.get("type", "")).startswith("INTERNAL_")
                   for item in leakage_report.get("findings", [])):
        raise FinalizationError("leakage audit contains internal duplicate findings")
    root_details = leakage_report.get("historyRootDetails")
    if not isinstance(root_details, list) or len(root_details) != len(history_roots):
        raise FinalizationError("leakage audit history root details are incomplete")
    details_by_root = {}
    for detail in root_details:
        if not isinstance(detail, dict):
            raise FinalizationError("leakage audit history root detail is invalid")
        root_value = str(detail.get("root", "")).strip()
        history_root = Path(root_value).resolve()
        if not history_root.is_dir():
            raise FinalizationError(f"leakage audit history root does not exist: {history_root}")
        snapshots = detail.get("contentSnapshots")
        file_count = detail.get("fileCount")
        if not isinstance(snapshots, list) or not isinstance(file_count, int) \
                or file_count <= 0 or file_count != len(snapshots):
            raise FinalizationError("leakage audit history root has no supported file")
        for snapshot in snapshots:
            if not isinstance(snapshot, dict) \
                    or not str(snapshot.get("path", "")).strip() \
                    or not str(snapshot.get("contentSha256", "")).strip() \
                    or not str(snapshot.get("normalizedTextSha256", "")).strip():
                raise FinalizationError("leakage audit history content snapshot is invalid")
        details_by_root[history_root.as_posix()] = detail
    expected_history_roots = {Path(value).resolve().as_posix() for value in history_roots}
    if len(expected_history_roots) != len(history_roots) \
            or expected_history_roots != set(details_by_root) \
            or len(details_by_root) != len(root_details):
        raise FinalizationError("leakage audit history roots do not match details")
    leakage_audit = {
        "schemaVersion": leakage_report["schemaVersion"],
        "status": leakage_report["status"],
        "reportPath": audit_path.relative_to(root).as_posix(),
        "reportSha256": file_sha256(audit_path),
        "intakeFingerprint": expected_fingerprint,
        "historyRoots": history_roots,
        "threshold": leakage_report.get("threshold"),
        "historyFileCount": counts.get("historyFiles"),
        "historyCandidateKeyCount": counts.get("historyCandidateKeys"),
        "auditProgramSha256": expected_audit_program_sha,
        "historyRootDetails": root_details,
    }

    roles = base.get("roles")
    if not isinstance(roles, list) or len(roles) != int(base.get("plannedRoleCount", -1)):
        raise FinalizationError("role catalog count does not match intake manifest")
    role_by_id = {}
    for role in roles:
        try:
            role_id = int(role.get("roleId"))
        except (AttributeError, TypeError, ValueError):
            raise FinalizationError("role catalog contains an invalid roleId") from None
        if role_id in role_by_id:
            raise FinalizationError("role catalog roleId must be unique")
        role_by_id[role_id] = role

    case_ids = set()
    pairs = set()
    sealed_cases = []
    allowed_case_fields = {
        "caseId", "resumeId", "roleId", "roleCode", "roleName", "roleVersion",
    }
    for row in cases:
        unknown_fields = set(row) - allowed_case_fields
        if unknown_fields:
            raise FinalizationError(
                f"case manifest contains forbidden label or unknown fields: {sorted(unknown_fields)}")
        _required(row, ("caseId", "resumeId", "roleId", "roleCode", "roleName", "roleVersion"), "case")
        case_id = row["caseId"].strip()
        resume_id = row["resumeId"].strip()
        role_id = int(row["roleId"])
        pair = (resume_id, role_id)
        role = role_by_id.get(role_id)
        if role is None or any(row[field].strip() != str(role.get(field, "")).strip()
                               for field in ("roleCode", "roleName", "roleVersion")):
            raise FinalizationError("case role identity does not match role catalog")
        if case_id in case_ids or pair in pairs or resume_id not in resume_ids:
            raise FinalizationError("caseId/候选人岗位对重复或引用未知简历")
        case_ids.add(case_id)
        pairs.add(pair)
        sealed_cases.append({**row, "roleId": role_id})

    expected_pairs = {(resume_id, role_id) for resume_id in resume_ids
                      for role_id in role_by_id}
    if pairs != expected_pairs:
        raise FinalizationError("case manifest must cover the complete resume-role matrix")

    artifact_paths = {
        "jarSha256": root / "backend/target/zhigang-backend-1.0.0.jar",
        "capabilityCardsSha256": root / "backend/src/main/resources/matching/role-capability-cards-v2.json",
        "resumeEvidenceExtractorSha256": root / "backend/src/main/java/com/zhigang/platform/career/ResumeEvidenceExtractor.java",
        "matchDecisionEngineSha256": root / "backend/src/main/java/com/zhigang/platform/career/MatchDecisionEngine.java",
    }
    missing_artifacts = [str(path) for path in artifact_paths.values() if not path.is_file()]
    if missing_artifacts:
        raise FinalizationError(f"缺少冻结运行制品: {missing_artifacts}")
    artifacts = {name: file_sha256(path) for name, path in artifact_paths.items()}
    expected_cards = str(base.get("roleCatalogSha256", "")).strip().casefold()
    if expected_cards and expected_cards != artifacts["capabilityCardsSha256"].casefold():
        raise FinalizationError("岗位能力卡哈希与 intake manifest 不一致")

    sealed = {
        **{key: value for key, value in base.items()
           if key not in {"resumes", "cases", "status", "schemaVersion"}},
        "schemaVersion": "match-final-blind-intake-v2.0",
        "datasetId": f"match-final-blind-{len(sealed_resumes)}x{len(base.get('roles', []))}-v1",
        "status": "FROZEN_FOR_PREDICTION",
        "sealedAt": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
        "evaluationContext": {
            "weeklyHours": 12,
            "graduationYear": 2027,
            "preferredCity": "",
            "workMode": "",
        },
        "leakageAudit": leakage_audit,
        "artifacts": artifacts,
        "resumes": sealed_resumes,
        "cases": sealed_cases,
    }
    sealed["datasetSha256"] = canonical_sha256(sealed)
    return sealed


def parse_args():
    root = Path(__file__).resolve().parents[1]
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--intake", type=Path, required=True)
    parser.add_argument("--root", type=Path, default=root)
    parser.add_argument("--output", type=Path, required=True)
    return parser.parse_args()


def main():
    args = parse_args()
    try:
        if args.output.exists():
            raise FinalizationError(f"拒绝覆盖已封存清单: {args.output}")
        sealed = finalize_intake(args.intake, args.root)
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(sealed, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        print(f"已封存 {len(sealed['resumes'])} 份简历、{len(sealed['cases'])} 个 case")
        return 0
    except (OSError, json.JSONDecodeError, FinalizationError) as error:
        print(f"错误: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
