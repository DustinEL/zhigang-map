from __future__ import annotations

import argparse
import csv
import hashlib
import json
import re
from collections import Counter
from difflib import SequenceMatcher
from pathlib import Path

from openpyxl import load_workbook


TEXT_FIELDS = (
    "inputText", "rawText", "jd", "jdText", "jobDescription", "job_description", "jobDesc",
    "chineseOriginalText", "JD原文", "原始JD", "岗位描述", "职位描述",
    "岗位正文（中文原文）", "岗位职责与任职要求",
)
RECORD_ID_FIELDS = ("recordId", "record_id", "id", "岗位ID", "编号")


def read_source_text(path: Path) -> str:
    data = path.read_bytes()
    if data.startswith((b"\xff\xfe", b"\xfe\xff")):
        return data.decode("utf-16")
    return data.decode("utf-8-sig")


def load_jsonl(path: Path) -> list[dict]:
    lines = re.split(r"\r\n?|\n", read_source_text(path))
    return [json.loads(line) for line in lines if line.strip()]


def mapping_record_id(row: dict, fallback: str) -> str:
    for field in RECORD_ID_FIELDS:
        value = row.get(field)
        if value is not None and str(value).strip():
            return str(value).strip()
    return fallback


def rows_from_json_value(value, source: Path, location: str = "root") -> list[dict]:
    rows = []
    if isinstance(value, dict):
        for field in TEXT_FIELDS:
            text = value.get(field)
            if isinstance(text, str) and text.strip():
                rows.append({
                    "recordId": mapping_record_id(value, f"{source}:{location}:{field}"),
                    "rawText": text,
                })
        for key, nested in value.items():
            if isinstance(nested, (dict, list)):
                rows.extend(rows_from_json_value(nested, source, f"{location}.{key}"))
    elif isinstance(value, list):
        for index, nested in enumerate(value):
            rows.extend(rows_from_json_value(nested, source, f"{location}[{index}]"))
    return rows


def rows_from_delimited(path: Path, delimiter: str) -> list[dict]:
    rows = []
    with path.open("r", encoding="utf-8-sig", newline="") as stream:
        for index, row in enumerate(csv.DictReader(stream, delimiter=delimiter), start=2):
            for field in TEXT_FIELDS:
                text = row.get(field)
                if isinstance(text, str) and text.strip():
                    rows.append({
                        "recordId": mapping_record_id(row, f"{path}:row-{index}:{field}"),
                        "rawText": text,
                    })
    return rows


def rows_from_xlsx(path: Path) -> list[dict]:
    rows = []
    workbook = load_workbook(path, read_only=True, data_only=True)
    try:
        for sheet in workbook.worksheets:
            iterator = sheet.iter_rows(values_only=True)
            headers = [str(value).strip() if value is not None else "" for value in next(iterator, ())]
            for index, values in enumerate(iterator, start=2):
                row = {headers[column]: value for column, value in enumerate(values) if column < len(headers) and headers[column]}
                for field in TEXT_FIELDS:
                    text = row.get(field)
                    if isinstance(text, str) and text.strip():
                        rows.append({
                            "recordId": mapping_record_id(row, f"{path}:{sheet.title}:row-{index}:{field}"),
                            "rawText": text,
                        })
    finally:
        workbook.close()
    return rows


def rows_from_text(path: Path) -> list[dict]:
    text = read_source_text(path)
    jd_markers = ("\u5c97\u4f4d\u804c\u8d23", "\u4efb\u804c\u8981\u6c42")
    if not all(marker in text for marker in jd_markers):
        return []
    return [{"recordId": str(path), "rawText": text}]


def split_mysql_values(values_sql: str) -> list[list[str | None]]:
    rows = []
    row = None
    value = []
    in_string = False
    quoted_value = False
    escape = False
    index = 0
    escapes = {"n": "\n", "r": "\r", "t": "\t", "b": "\b", "0": "\0", "Z": "\x1a"}
    while index < len(values_sql):
        char = values_sql[index]
        if row is None:
            if char == ";":
                break
            if char == "(":
                row, value, quoted_value = [], [], False
            index += 1
            continue
        if in_string:
            if escape:
                value.append(escapes.get(char, char))
                escape = False
            elif char == "\\":
                escape = True
            elif char == "'":
                if index + 1 < len(values_sql) and values_sql[index + 1] == "'":
                    value.append("'")
                    index += 1
                else:
                    in_string = False
            else:
                value.append(char)
        elif char == "'":
            in_string = True
            quoted_value = True
        elif char in {",", ")"}:
            raw_value = "".join(value) if quoted_value else "".join(value).strip()
            row.append(None if not quoted_value and raw_value.casefold() == "null" else raw_value)
            value, quoted_value = [], False
            if char == ")":
                rows.append(row)
                row = None
        else:
            value.append(char)
        index += 1
    return rows


def rows_from_mysql_dump(path: Path) -> list[dict]:
    sql = read_source_text(path)
    create_match = re.search(
        r"CREATE\s+TABLE\s+`?raw_document`?\s*\((.*?)\)\s*(?:ENGINE|;)",
        sql,
        flags=re.IGNORECASE | re.DOTALL,
    )
    if not create_match:
        return []
    default_columns = re.findall(r"^\s*`([^`]+)`\s+", create_match.group(1), flags=re.MULTILINE)
    rows = []
    insert_pattern = re.compile(
        r"INSERT\s+INTO\s+`?raw_document`?\s*(?:\(([^)]*)\))?\s*VALUES\s*",
        flags=re.IGNORECASE,
    )
    for insert_number, match in enumerate(insert_pattern.finditer(sql), start=1):
        columns = default_columns
        if match.group(1):
            columns = re.findall(r"`?([A-Za-z0-9_]+)`?", match.group(1))
        if "raw_text" not in columns:
            continue
        text_index = columns.index("raw_text")
        id_index = columns.index("id") if "id" in columns else None
        for row_number, values in enumerate(split_mysql_values(sql[match.end():]), start=1):
            if text_index >= len(values) or not values[text_index]:
                continue
            record_id = values[id_index] if id_index is not None and id_index < len(values) else None
            rows.append({
                "recordId": str(record_id or f"{path}:insert-{insert_number}:row-{row_number}"),
                "rawText": values[text_index],
            })
    return rows


def discover_exposed_rows(roots: list[Path]) -> tuple[list[dict], dict[str, int], list[str]]:
    rows = []
    files_by_type = Counter()
    unsupported = []
    supported = {".json", ".jsonl", ".csv", ".tsv", ".xlsx", ".sql", ".txt"}
    files = []
    for root in roots:
        files.extend([root] if root.is_file() else sorted(path for path in root.rglob("*") if path.is_file()))
    for path in files:
        suffix = path.suffix.casefold()
        if suffix not in supported:
            unsupported.append(str(path))
            continue
        try:
            if suffix == ".jsonl":
                extracted = [row for value in load_jsonl(path) for row in rows_from_json_value(value, path)]
            elif suffix == ".json":
                extracted = rows_from_json_value(json.loads(read_source_text(path)), path)
            elif suffix in {".csv", ".tsv"}:
                extracted = rows_from_delimited(path, "\t" if suffix == ".tsv" else ",")
            elif suffix == ".xlsx":
                extracted = rows_from_xlsx(path)
            elif suffix == ".txt":
                extracted = rows_from_text(path)
            else:
                extracted = rows_from_mysql_dump(path)
        except (OSError, UnicodeError, ValueError, json.JSONDecodeError):
            unsupported.append(str(path))
            continue
        if extracted:
            files_by_type[suffix] += 1
            rows.extend(extracted)
    unique_rows = []
    seen = set()
    for row in rows:
        fingerprint = sha256(normalize(row_text(row)))
        if fingerprint in seen:
            continue
        seen.add(fingerprint)
        unique_rows.append(row)
    return unique_rows, dict(sorted(files_by_type.items())), unsupported


def normalize(text: str) -> str:
    return re.sub(r"[^0-9a-z\u4e00-\u9fff]+", "", text.casefold())


def sha256(text: str) -> str:
    return hashlib.sha256(text.encode("utf-8")).hexdigest()


def row_text(row: dict) -> str:
    for field in ("inputText", "rawText", "jd"):
        value = row.get(field)
        if isinstance(value, str) and value.strip():
            return value
    raise ValueError(f"recordId={row.get('recordId')} 缺少非空 inputText/rawText")


def threshold_similarity(left: str, right: str, threshold: float) -> float | None:
    if not left or not right:
        return None
    if 2 * min(len(left), len(right)) / (len(left) + len(right)) < threshold:
        return None
    matcher = SequenceMatcher(None, left, right, autojunk=False)
    if matcher.real_quick_ratio() < threshold or matcher.quick_ratio() < threshold:
        return None
    similarity = matcher.ratio()
    return similarity if similarity >= threshold else None


def audit(candidates: list[dict], exposed: list[dict], near_threshold: float) -> list[dict]:
    candidate_rows = [(row, normalize(row_text(row))) for row in candidates]
    exposed_rows = [(row, normalize(row_text(row))) for row in exposed]
    findings = []
    for index, (candidate, candidate_text) in enumerate(candidate_rows):
        for other, other_text in candidate_rows[index + 1:]:
            similarity = threshold_similarity(candidate_text, other_text, near_threshold)
            if similarity is None:
                continue
            findings.append({
                "scope": "candidate-internal",
                "candidateRecordId": candidate.get("recordId"),
                "exposedRecordId": other.get("recordId"),
                "matchType": "exact" if candidate_text == other_text else "near",
                "similarity": round(similarity, 6),
                "candidateTextSha256": sha256(candidate_text),
                "exposedTextSha256": sha256(other_text),
            })
        best = None
        for exposed_row, exposed_text in exposed_rows:
            similarity = threshold_similarity(candidate_text, exposed_text, near_threshold)
            if similarity is None:
                continue
            if best is None or similarity > best[0]:
                best = similarity, exposed_row, exposed_text
        if best is None:
            continue
        similarity, exposed_row, exposed_text = best
        findings.append({
            "scope": "candidate-exposed",
            "candidateRecordId": candidate.get("recordId"),
            "exposedRecordId": exposed_row.get("recordId"),
            "matchType": "exact" if candidate_text == exposed_text else "near",
            "similarity": round(similarity, 6),
            "candidateTextSha256": sha256(candidate_text),
            "exposedTextSha256": sha256(exposed_text),
        })
    return findings


def main() -> int:
    parser = argparse.ArgumentParser(description="阻断独立 JD 评测集与已暴露数据的文本泄漏")
    parser.add_argument("--candidate", type=Path, required=True)
    parser.add_argument("--exposed", type=Path, action="append", default=[])
    parser.add_argument("--exposed-root", type=Path, action="append", default=[])
    parser.add_argument("--report", type=Path, required=True)
    parser.add_argument("--near-threshold", type=float, default=0.92)
    args = parser.parse_args()
    if not 0.0 < args.near_threshold <= 1.0:
        parser.error("--near-threshold 必须在 (0, 1] 范围内")
    if not args.exposed and not args.exposed_root:
        parser.error("至少需要一个 --exposed 或 --exposed-root")

    candidates = load_jsonl(args.candidate)
    direct_exposed = [row for path in args.exposed for row in load_jsonl(path)]
    discovered, exposed_files_by_type, unsupported_files = discover_exposed_rows(args.exposed_root)
    exposed = direct_exposed + discovered
    findings = audit(candidates, exposed, args.near_threshold)
    payload = {
        "candidateCount": len(candidates),
        "exposedCount": len(exposed),
        "exposedFilesByType": exposed_files_by_type,
        "unsupportedFiles": unsupported_files,
        "nearThreshold": args.near_threshold,
        "passed": not findings,
        "findings": findings,
    }
    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.report.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps({"passed": not findings, "findingCount": len(findings)}, ensure_ascii=False))
    return 0 if not findings else 2


if __name__ == "__main__":
    raise SystemExit(main())
