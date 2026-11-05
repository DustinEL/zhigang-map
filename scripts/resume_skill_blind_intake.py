from __future__ import annotations

import hashlib
import json
import re
import csv
from dataclasses import dataclass
from pathlib import Path

from docx import Document
from pypdf import PdfReader
from openpyxl import Workbook
from openpyxl.styles import Alignment, Font, PatternFill


SAMPLE_NAME = re.compile(r"^(\d{2})_")
EMAIL = re.compile(r"[A-Z0-9._%+-]+@[A-Z0-9.-]+\.[A-Z]{2,}", re.IGNORECASE)
GITHUB = re.compile(r"(?:https?://)?(?:www\.)?github\.com/[A-Z0-9_.-]+", re.IGNORECASE)
LINKEDIN = re.compile(r"(?:https?://)?(?:www\.)?linkedin\.com/in/[A-Z0-9_%.-]+", re.IGNORECASE)
TOKEN = re.compile(r"[A-Z0-9_+#.-]+|[\u4e00-\u9fff]", re.IGNORECASE)
CODE_LINE = re.compile(
    r"^\s*(?:import\s|export\s|const\s|let\s|var\s|function\s|class\s|from\s+\S+\s+import\s|"
    r"[{}<>]|package\.json\b|package-lock\.json\b|npm\s+(?:install|run)\b|yarn\s|pnpm\s|"
    r"\./(?:src|components|pages|lib)/)",
    re.IGNORECASE,
)
SECTION_PATTERNS = {
    "skills": re.compile(r"\b(?:skills?|technologies|tech stack)\b|技能", re.IGNORECASE),
    "projects": re.compile(r"\bprojects?\b|项目", re.IGNORECASE),
    "experience": re.compile(r"\b(?:work )?experience\b|employment|经历|工作", re.IGNORECASE),
    "education": re.compile(r"\beducation\b|教育", re.IGNORECASE),
}


@dataclass(frozen=True)
class SampleFiles:
    sample_id: str
    directory: Path
    docx: Path
    pdf: Path
    source_zip: Path


def select_sample_files(sample_dir: Path) -> SampleFiles:
    match = SAMPLE_NAME.match(sample_dir.name)
    if not match:
        raise ValueError(f"样本目录必须以两位编号开头: {sample_dir.name}")
    sample_id = match.group(1)

    def numbered(extension: str) -> list[Path]:
        return sorted(path for path in sample_dir.iterdir()
                      if path.is_file() and path.suffix.lower() == extension
                      and path.name.startswith(f"{sample_id}_"))

    docx_files = numbered(".docx")
    pdf_files = numbered(".pdf")
    zip_files = sorted(path for path in sample_dir.iterdir() if path.is_file() and path.suffix.lower() == ".zip")
    if len(docx_files) != 1:
        raise ValueError(f"样本 {sample_dir.name} 的编号 DOCX 数量必须为 1，实际为 {len(docx_files)}")
    if len(pdf_files) != 1:
        raise ValueError(f"样本 {sample_dir.name} 的编号 PDF 数量必须为 1，实际为 {len(pdf_files)}")
    if len(zip_files) != 1:
        raise ValueError(f"样本 {sample_dir.name} 的来源 ZIP 数量必须为 1，实际为 {len(zip_files)}")
    return SampleFiles(sample_id, sample_dir, docx_files[0], pdf_files[0], zip_files[0])


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def extract_docx_text(path: Path) -> str:
    document = Document(path)
    lines = [paragraph.text.strip() for paragraph in document.paragraphs if paragraph.text.strip()]
    for table in document.tables:
        for row in table.rows:
            line = " | ".join(cell.text.strip() for cell in row.cells if cell.text.strip())
            if line:
                lines.append(line)
    return "\n".join(lines)


def extract_pdf_text(path: Path) -> str:
    return "\n".join((page.extract_text() or "").strip() for page in PdfReader(path).pages).strip()


def normalized_tokens(text: str) -> list[str]:
    return [token.casefold() for token in TOKEN.findall(text)]


def normalized_text_hash(text: str) -> str:
    return hashlib.sha256(" ".join(normalized_tokens(text)).encode("utf-8")).hexdigest()


def shingle_jaccard(left: str, right: str, size: int = 5) -> float:
    def shingles(text: str) -> set[tuple[str, ...]]:
        tokens = normalized_tokens(text)
        if not tokens:
            return set()
        if len(tokens) < size:
            return {tuple(tokens)}
        return {tuple(tokens[index:index + size]) for index in range(len(tokens) - size + 1)}

    left_shingles = shingles(left)
    right_shingles = shingles(right)
    if not left_shingles and not right_shingles:
        return 1.0
    union = left_shingles | right_shingles
    return len(left_shingles & right_shingles) / len(union) if union else 0.0


def identity_from_text(text: str) -> dict[str, list[str]]:
    def normalize_url(value: str) -> str:
        return re.sub(r"^(?:https?://)?(?:www\.)?", "", value.casefold()).rstrip("/.,);]")

    candidate_github = set()
    source_window = 0
    for line in text.splitlines():
        lowered = line.casefold()
        if "公开来源" in line or "原始来源" in line or re.search(r"\bsource\b", lowered):
            source_window = 3
        if "公开履历正文" in line:
            source_window = 0
        is_contact_line = bool(re.match(r"^\s*(?:[•*-]\s*)?(?:github|github主页)\s*[:：]", line, re.IGNORECASE))
        if source_window > 0 or is_contact_line:
            candidate_github.update(normalize_url(value) for value in GITHUB.findall(line))
        if source_window > 0:
            source_window -= 1

    emails = {
        value.casefold() for value in EMAIL.findall(text)
        if "example." not in value.casefold() and not value.casefold().startswith("your@")
    }
    return {
        "emails": sorted(emails),
        "github": sorted(candidate_github),
        "linkedin": sorted({normalize_url(value) for value in LINKEDIN.findall(text)}),
    }


def code_line_ratio(text: str) -> float:
    lines = [line.strip() for line in text.splitlines() if line.strip()]
    if not lines:
        return 0.0
    return sum(1 for line in lines if CODE_LINE.search(line)) / len(lines)


def resume_sections(text: str) -> list[str]:
    return sorted(name for name, pattern in SECTION_PATTERNS.items() if pattern.search(text))


def admission_failures(docx_text: str, pdf_text: str, pdf_similarity: float) -> list[str]:
    failures = []
    if len(resume_sections(docx_text)) < 2:
        failures.append("INSUFFICIENT_RESUME_SECTIONS")
    if code_line_ratio(docx_text) > 0.10:
        failures.append("CODE_CONTENT_OVER_10_PERCENT")
    if not pdf_text.strip() or pdf_similarity < 0.95:
        failures.append("DOCX_PDF_CONTENT_MISMATCH")
    return failures


def _identity_matches(left: dict, right: dict) -> list[str]:
    fields = []
    for field in ("emails", "github", "linkedin"):
        left_values = {value.casefold().rstrip("/") for value in left.get(field, [])}
        right_values = {value.casefold().rstrip("/") for value in right.get(field, [])}
        if left_values & right_values:
            fields.append(field)
    return fields


def audit_independence(new_records: list[dict], old_records: list[dict]) -> dict:
    exact_file_duplicates = []
    identity_duplicates = []
    near_duplicates = []
    comparisons = []
    for new_index, new in enumerate(new_records):
        candidates = [("OLD", old) for old in old_records]
        candidates += [("NEW", other) for other in new_records[new_index + 1:]]
        for collection, other in candidates:
            matched_fields = _identity_matches(new.get("identity", {}), other.get("identity", {}))
            shared_hashes = sorted(
                set(new.get("fileHashes", {}).values()) & set(other.get("fileHashes", {}).values())
            )
            similarity = shingle_jaccard(new.get("text", ""), other.get("text", ""))
            comparison = {
                "newSampleId": new["sampleId"],
                "otherCollection": collection,
                "otherSampleId": other["sampleId"],
                "similarity": round(similarity, 6),
                "identityFields": matched_fields,
                "sharedFileHashes": shared_hashes,
            }
            comparisons.append(comparison)
            if shared_hashes:
                exact_file_duplicates.append(comparison)
            if matched_fields:
                identity_duplicates.append({
                    **comparison,
                    "oldSampleId": other["sampleId"] if collection == "OLD" else None,
                })
            if similarity >= 0.65:
                near_duplicates.append({**comparison, "decision": "REJECT" if similarity >= 0.80 else "MANUAL_REVIEW"})
    if exact_file_duplicates or identity_duplicates or any(item["decision"] == "REJECT" for item in near_duplicates):
        status = "FAIL"
    elif any(item["decision"] == "MANUAL_REVIEW" for item in near_duplicates):
        status = "REVIEW_REQUIRED"
    else:
        status = "PASS"
    return {
        "status": status,
        "exactFileDuplicates": exact_file_duplicates,
        "identityDuplicates": identity_duplicates,
        "nearDuplicates": near_duplicates,
        "comparisons": comparisons,
    }


def _file_record(path: Path, project_root: Path) -> dict:
    return {
        "path": path.resolve().relative_to(project_root.resolve()).as_posix(),
        "sha256": sha256_file(path),
        "bytes": path.stat().st_size,
    }


def _read_old_records(manifest_path: Path | None, project_root: Path) -> list[dict]:
    if manifest_path is None:
        return []
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    records = []
    for sample in manifest["samples"]:
        docx_path = project_root / sample["docx"]["path"]
        text = extract_docx_text(docx_path)
        records.append({
            "sampleId": sample["sampleId"],
            "text": text,
            "identity": identity_from_text(text),
            "fileHashes": {
                "docx": sample["docx"]["sha256"],
                "pdf": sample["pdf"]["sha256"],
            },
        })
    return records


def audit_dataset(resume_dir: Path, old_manifest_path: Path | None, project_root: Path,
                  expected_count: int = 30) -> tuple[dict, dict, dict]:
    directories = sorted(path for path in resume_dir.iterdir() if path.is_dir())
    actual_ids = [SAMPLE_NAME.match(path.name).group(1) if SAMPLE_NAME.match(path.name) else "" for path in directories]
    expected_ids = [f"{number:02d}" for number in range(1, expected_count + 1)]
    if len(directories) != expected_count or actual_ids != expected_ids:
        raise ValueError(f"样本数量和编号必须连续为 01-{expected_count:02d}，实际为 {actual_ids}")

    manifest_samples = []
    audit_records = []
    eligibility_samples = []
    for directory in directories:
        files = select_sample_files(directory)
        if any(path.stat().st_size == 0 for path in (files.docx, files.pdf, files.source_zip)):
            raise ValueError(f"样本 {files.sample_id} 包含空文件")
        docx_text = extract_docx_text(files.docx)
        pdf_text = extract_pdf_text(files.pdf)
        similarity = shingle_jaccard(docx_text, pdf_text)
        failures = admission_failures(docx_text, pdf_text, similarity)
        docx_record = _file_record(files.docx, project_root)
        pdf_record = _file_record(files.pdf, project_root)
        zip_record = _file_record(files.source_zip, project_root)
        manifest_samples.append({
            "sampleId": files.sample_id,
            "sampleName": directory.name,
            "docx": {**docx_record, "normalizedTextSha256": normalized_text_hash(docx_text)},
            "pdf": {**pdf_record, "normalizedTextSha256": normalized_text_hash(pdf_text)},
            "sourceZip": zip_record,
        })
        audit_records.append({
            "sampleId": files.sample_id,
            "text": docx_text,
            "identity": identity_from_text(docx_text),
            "fileHashes": {"docx": docx_record["sha256"], "pdf": pdf_record["sha256"], "zip": zip_record["sha256"]},
        })
        eligibility_samples.append({
            "sampleId": files.sample_id,
            "resumeSections": resume_sections(docx_text),
            "codeLineRatio": round(code_line_ratio(docx_text), 6),
            "docxPdfJaccard": round(similarity, 6),
            "failures": failures,
        })

    manifest = {
        "version": "resume-skill-blind-manifest-v1",
        "status": "INTAKE_AUDITED",
        "sampleCount": len(manifest_samples),
        "samples": manifest_samples,
    }
    manifest["datasetSha256"] = stable_dataset_hash(manifest_samples)
    independence = audit_independence(audit_records, _read_old_records(old_manifest_path, project_root))
    failed_samples = [sample for sample in eligibility_samples if sample["failures"]]
    eligibility = {
        "status": "PASS" if not failed_samples else "FAIL",
        "thresholds": {"docxPdfJaccard": 0.95, "maxCodeLineRatio": 0.10, "minimumResumeSections": 2},
        "samples": eligibility_samples,
        "failedSampleIds": [sample["sampleId"] for sample in failed_samples],
    }
    return manifest, independence, eligibility


def stable_dataset_hash(samples: list[dict]) -> str:
    payload = json.dumps(samples, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
    return hashlib.sha256(payload.encode("utf-8")).hexdigest()


def _write_json_new(path: Path, value: object) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    try:
        with path.open("x", encoding="utf-8") as stream:
            json.dump(value, stream, ensure_ascii=False, indent=2)
            stream.write("\n")
    except FileExistsError as exception:
        raise FileExistsError(f"refusing to overwrite existing artifact: {path}") from exception


def _write_text_new(path: Path, content: str) -> None:
    try:
        with path.open("x", encoding="utf-8") as stream:
            stream.write(content.rstrip() + "\n")
    except FileExistsError as exception:
        raise FileExistsError(f"refusing to overwrite existing artifact: {path}") from exception


def _write_annotation_materials(output_dir: Path, manifest: dict) -> None:
    summary_headers = [
        "样本编号", "样本名称", "DOCX文件", "DOCX SHA-256", "标注状态", "标注人", "标注日期",
        "复核结论", "复核人", "复核日期", "复核理由",
    ]
    evidence_headers = [
        "样本编号", "标准技能", "原词", "章节", "定位", "原文引用", "命名来源",
        "初标决定", "不确定原因", "标注备注", "复核决定", "复核理由",
    ]
    double_headers = ["样本编号", "用户独立技能（用|分隔）", "标注人", "标注日期", "备注", "裁决状态", "裁决说明"]
    double_ids = {"01", "06", "12", "19", "21", "24"}

    workbook = Workbook()
    instructions = workbook.active
    instructions.title = "标注说明"
    instructions.append(["规则", "内容"])
    instructions.append(["独立性", "工作簿不得包含或引用程序预测；只依据 DOCX 可见正文标注。"])
    instructions.append(["开放世界", "正文明确出现的新技能照实标注，命名来源填 NEW_BLIND。"])
    instructions.append(["逐份复核", "每份必须单独填写复核结论、复核人、日期和理由，禁止批量传播通过。"])
    instructions.append(["双标样本", "01、06、12、19、21、24 由用户在查看 Codex 初标前独立标注。"])

    summary = workbook.create_sheet("样本汇总")
    summary.append(summary_headers)
    for sample in manifest.get("samples", []):
        summary.append([
            sample["sampleId"], sample["sampleName"], sample["docx"]["path"], sample["docx"]["sha256"],
            "未标注", "", "", "待复核", "", "", "",
        ])

    evidence = workbook.create_sheet("技能证据")
    evidence.append(evidence_headers)
    double = workbook.create_sheet("独立双标")
    double.append(double_headers)
    for sample in manifest.get("samples", []):
        if sample["sampleId"] in double_ids:
            double.append([sample["sampleId"], "", "", "", "", "", ""])

    for sheet in workbook.worksheets:
        for cell in sheet[1]:
            cell.font = Font(bold=True, color="FFFFFF")
            cell.fill = PatternFill("solid", fgColor="1F4E78")
            cell.alignment = Alignment(horizontal="center", vertical="center", wrap_text=True)
        sheet.freeze_panes = "A2"
        sheet.sheet_view.showGridLines = False
        for column in sheet.columns:
            sheet.column_dimensions[column[0].column_letter].width = 24

    workbook_path = output_dir / "resume-skill-blind-30-annotation.xlsx"
    if workbook_path.exists():
        raise FileExistsError(f"refusing to overwrite existing artifact: {workbook_path}")
    workbook.save(workbook_path)

    csv_path = output_dir / "resume-skill-blind-30-annotation.csv"
    try:
        with csv_path.open("x", encoding="utf-8-sig", newline="") as stream:
            writer = csv.writer(stream)
            writer.writerow(summary_headers)
            for sample in manifest.get("samples", []):
                writer.writerow([
                    sample["sampleId"], sample["sampleName"], sample["docx"]["path"], sample["docx"]["sha256"],
                    "未标注", "", "", "待复核", "", "", "",
                ])
    except FileExistsError as exception:
        raise FileExistsError(f"refusing to overwrite existing artifact: {csv_path}") from exception


def publish_intake(output_dir: Path, manifest: dict, independence: dict, eligibility: dict) -> Path:
    dataset_hash = manifest["datasetSha256"]
    audit_payload = json.dumps(
        {"independence": independence, "eligibility": eligibility},
        ensure_ascii=False, sort_keys=True, separators=(",", ":"),
    )
    audit_hash = hashlib.sha256(audit_payload.encode("utf-8")).hexdigest()
    audit_dir = output_dir / "intake-audits" / dataset_hash / audit_hash
    _write_json_new(audit_dir / "intake-manifest.json", manifest)
    _write_json_new(audit_dir / "independence-audit.json", independence)
    _write_json_new(audit_dir / "eligibility-review.json", eligibility)

    if independence.get("status") != "PASS" or eligibility.get("status") != "PASS":
        return audit_dir

    output_dir.mkdir(parents=True, exist_ok=True)
    _write_json_new(output_dir / "resume-skill-blind-30-manifest.json", manifest)
    _write_json_new(output_dir / "resume-skill-blind-30-independence-audit.json", independence)
    _write_json_new(output_dir / "resume-skill-blind-30-eligibility-review.json", eligibility)
    _write_text_new(output_dir / "resume-skill-blind-30-protocol.md", """
# 30 份简历独立盲测预注册协议

1. 样本、准入审计、生产源码与评测实现必须在人工初标前冻结。
2. 初标和复核期间禁止生成、查看或引用程序预测，禁止修改生产技能词表、别名和归一规则。
3. DOCX 计入指标，PDF 只验证技能集合一致性；ZIP 仅保留来源和哈希。
4. 首次报告硬门槛：micro Precision、Recall、F1 均不低于 90%，每份 Recall 不低于 80%，30 组 DOCX/PDF 完全一致，完整性失败为 0。
5. 置信区间使用简历级 bootstrap，固定随机种子 20260816、10,000 次抽样。
6. 首次报告不可覆盖；揭盲后本数据集转为回归集，后续结果只能称为调优后回归。
""")
    _write_text_new(output_dir / "resume-skill-blind-30-annotation-guidelines.md", """
# 独立盲测技能标注规范

- 只标注 DOCX 可见正文中明确出现的技术技能，不根据岗位、专业或常识推断。
- 语言、框架、库、数据库、中间件、云平台、协议、硬件平台、测试和安全工具可以计入；软技能和泛化活动不计入。
- 复合技能不自动产生内部短技能；C、C++、C# 分开；版本号默认折叠到既有标准名。
- 旧标准名存在时填 `V1`；新出现的真实技能采用官方名称并填 `NEW_BLIND`，不得迁就当前程序词表。
- 每个技能必须填写原词、章节、定位和足以判断语境的原文引用。
- 不确定项必须在复核前逐项裁决；每份必须独立填写复核人、日期、结论和理由，禁止批量传播通过。
- 01、06、12、19、21、24 在查看 Codex 初标前由用户独立双标。
""")
    _write_annotation_materials(output_dir, manifest)
    return audit_dir
