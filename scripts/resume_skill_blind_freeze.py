from __future__ import annotations

import json
from datetime import date, datetime
from pathlib import Path

from openpyxl import Workbook


DOUBLE_IDS = {"01", "06", "12", "19", "21", "24"}


def iso_date(value) -> str:
    if isinstance(value, datetime):
        return value.date().isoformat()
    if isinstance(value, date):
        return value.isoformat()
    return str(value or "").strip()


def _rows_by_header(sheet) -> list[dict]:
    headers = [str(cell.value or "").strip() for cell in sheet[1]]
    if len(headers) != len(set(headers)):
        raise ValueError(f"工作表 {sheet.title} 包含重复表头")
    return [
        {headers[index]: cell.value for index, cell in enumerate(row)}
        for row in sheet.iter_rows(min_row=2)
        if any(cell.value not in (None, "") for cell in row)
    ]


def validate_double_annotation(workbook: Workbook, manifest: dict) -> dict:
    manifest_ids = {sample["sampleId"] for sample in manifest["samples"]}
    expected_ids = sorted(DOUBLE_IDS & manifest_ids)
    if not expected_ids:
        return {"status": "NOT_REQUIRED", "sampleCount": 0, "microF1": None, "samples": []}

    evidence_rows = _rows_by_header(workbook["技能证据"])
    double_rows = {str(row["样本编号"]).zfill(2): row for row in _rows_by_header(workbook["独立双标"])}
    sample_results = []
    total_tp = total_predicted = total_expected = 0
    for sample_id in expected_ids:
        row = double_rows.get(sample_id)
        if row is None or not row.get("用户独立技能（用|分隔）") or not row.get("标注人") or not row.get("标注日期"):
            raise ValueError(f"双标样本 {sample_id} 的用户独立标注不完整")
        codex = {
            str(item.get("标准技能") or "").strip()
            for item in evidence_rows
            if str(item.get("样本编号")).zfill(2) == sample_id and item.get("初标决定") == "INCLUDE"
        }
        codex.discard("")
        user = {value.strip() for value in str(row["用户独立技能（用|分隔）"]).split("|") if value.strip()}
        if not codex or not user:
            raise ValueError(f"双标样本 {sample_id} 的技能集合为空")
        intersection = len(codex & user)
        union = len(codex | user)
        jaccard = intersection / union if union else 1.0
        if codex != user and (row.get("裁决状态") != "RESOLVED" or not row.get("裁决说明")):
            raise ValueError(f"双标样本 {sample_id} 的分歧尚未裁决")
        sample_results.append({
            "sampleId": sample_id,
            "codexSkills": sorted(codex),
            "userSkills": sorted(user),
            "jaccard": jaccard,
            "adjudicationStatus": row.get("裁决状态") or "NOT_NEEDED",
        })
        total_tp += intersection
        total_predicted += len(codex)
        total_expected += len(user)
    precision = total_tp / total_predicted if total_predicted else 0.0
    recall = total_tp / total_expected if total_expected else 0.0
    micro_f1 = 2 * precision * recall / (precision + recall) if precision + recall else 0.0
    if micro_f1 < 0.90 or any(sample["jaccard"] < 0.80 for sample in sample_results):
        raise ValueError(f"双标一致性未达标: microF1={micro_f1:.4f}, samples={sample_results}")
    return {"status": "PASS", "sampleCount": len(sample_results), "microF1": micro_f1, "samples": sample_results}


def export_initial_annotations(workbook: Workbook, manifest: dict) -> dict:
    summary_rows = {str(row["样本编号"]).zfill(2): row for row in _rows_by_header(workbook["样本汇总"])}
    evidence_rows = _rows_by_header(workbook["技能证据"])
    annotations = {}
    for sample in manifest["samples"]:
        sample_id = sample["sampleId"]
        summary = summary_rows.get(sample_id)
        if summary is None:
            raise ValueError(f"初标缺少样本汇总 {sample_id}")
        evidence = []
        for row in evidence_rows:
            if str(row.get("样本编号")).zfill(2) != sample_id or row.get("初标决定") != "INCLUDE":
                continue
            skill = str(row.get("标准技能") or "").strip()
            if not skill or not row.get("原词") or not row.get("原文引用"):
                raise ValueError(f"样本 {sample_id} 的初标证据不完整")
            evidence.append({
                "canonicalSkill": skill,
                "surfaceForm": str(row["原词"]).strip(),
                "section": str(row.get("章节") or "").strip(),
                "locator": str(row.get("定位") or "").strip(),
                "exactQuote": str(row["原文引用"]).strip(),
                "ontologySource": str(row.get("命名来源") or "").strip(),
                "uncertainty": str(row.get("不确定原因") or "").strip(),
                "annotatorNote": str(row.get("标注备注") or "").strip(),
            })
        skills = [item["canonicalSkill"] for item in evidence]
        if not skills:
            raise ValueError(f"样本 {sample_id} 没有初标技能")
        annotations[sample_id] = {
            "skills": skills,
            "evidence": evidence,
            "annotator": str(summary.get("标注人") or ""),
            "annotationDate": iso_date(summary.get("标注日期")),
        }
    return {
        "version": "resume-skill-blind-initial-annotation-v1",
        "datasetSha256": manifest["datasetSha256"],
        "reviewStatus": "PENDING_HUMAN_REVIEW",
        "annotations": annotations,
    }


def build_gold(workbook: Workbook, manifest: dict, baseline_source_hash: str) -> dict:
    double_annotation = validate_double_annotation(workbook, manifest)
    summary_rows = _rows_by_header(workbook["样本汇总"])
    evidence_rows = _rows_by_header(workbook["技能证据"])
    manifest_by_id = {sample["sampleId"]: sample for sample in manifest["samples"]}
    summary_by_id = {str(row["样本编号"]).zfill(2): row for row in summary_rows}
    if len(summary_by_id) != manifest["sampleCount"] or set(summary_by_id) != set(manifest_by_id):
        raise ValueError("样本汇总与 manifest 样本集合不一致")

    evidence_by_id: dict[str, list[dict]] = {sample_id: [] for sample_id in manifest_by_id}
    for row in evidence_rows:
        sample_id = str(row["样本编号"]).zfill(2)
        if sample_id not in manifest_by_id:
            raise ValueError(f"技能证据引用未知样本 {sample_id}")
        evidence_by_id[sample_id].append(row)

    samples = []
    for sample_id, manifest_sample in manifest_by_id.items():
        summary = summary_by_id[sample_id]
        if summary.get("标注状态") != "已复核" or summary.get("复核结论") != "通过":
            raise ValueError(f"样本 {sample_id} 尚未逐份复核通过")
        if not summary.get("复核人") or not summary.get("复核日期") or not summary.get("复核理由"):
            raise ValueError(f"样本 {sample_id} 的复核信息不完整")
        if str(summary.get("DOCX SHA-256") or "") != manifest_sample["docx"]["sha256"]:
            raise ValueError(f"样本 {sample_id} 的 DOCX SHA-256 与 manifest 不一致")

        accepted = []
        for row in evidence_by_id[sample_id]:
            decision = str(row.get("复核决定") or "").strip()
            if decision not in {"ACCEPT", "REMOVE", "RENAME"}:
                raise ValueError(f"样本 {sample_id} 存在未裁决技能证据")
            if not row.get("复核理由"):
                raise ValueError(f"样本 {sample_id} 的技能复核理由为空")
            if decision == "REMOVE":
                continue
            skill = str(row.get("标准技能") or "").strip()
            if not skill or not row.get("原词") or not row.get("章节") or not row.get("定位") or not row.get("原文引用"):
                raise ValueError(f"样本 {sample_id} 的技能证据不完整")
            if row.get("初标决定") != "INCLUDE" or row.get("不确定原因"):
                raise ValueError(f"样本 {sample_id} 仍有未裁决不确定项")
            ontology = str(row.get("命名来源") or "").strip()
            if ontology not in {"V1", "NEW_BLIND"}:
                raise ValueError(f"样本 {sample_id} 的命名来源无效")
            accepted.append({
                "canonicalSkill": skill,
                "surfaceForm": str(row["原词"]).strip(),
                "section": str(row["章节"]).strip(),
                "locator": str(row["定位"]).strip(),
                "exactQuote": str(row["原文引用"]).strip(),
                "ontologySource": ontology,
                "reviewDecision": decision,
                "reviewerRationale": str(row["复核理由"]).strip(),
            })
        skills = [item["canonicalSkill"] for item in accepted]
        if not skills:
            raise ValueError(f"样本 {sample_id} 没有已复核技能")
        if len(skills) != len(set(skills)):
            raise ValueError(f"样本 {sample_id} 包含重复标准技能")
        samples.append({
            "sampleId": sample_id,
            "sampleName": manifest_sample["sampleName"],
            "docxPath": manifest_sample["docx"]["path"],
            "docxSha256": manifest_sample["docx"]["sha256"],
            "pdfPath": manifest_sample["pdf"]["path"],
            "pdfSha256": manifest_sample["pdf"]["sha256"],
            "sourceZipPath": manifest_sample["sourceZip"]["path"],
            "sourceZipSha256": manifest_sample["sourceZip"]["sha256"],
            "skills": skills,
            "evidence": accepted,
            "annotator": str(summary.get("标注人") or ""),
            "annotationDate": iso_date(summary.get("标注日期")),
            "reviewer": str(summary["复核人"]),
            "reviewDate": iso_date(summary["复核日期"]),
            "reviewRationale": str(summary["复核理由"]),
        })
    return {
        "version": "resume-skill-blind-gold-v1",
        "goldStatus": "FROZEN",
        "datasetSha256": manifest["datasetSha256"],
        "baselineSourceTreeSha256": baseline_source_hash,
        "sampleCount": len(samples),
        "doubleAnnotation": double_annotation,
        "samples": samples,
    }


def freeze_gold(workbook: Workbook, manifest: dict, baseline_source_hash: str, output_path: Path) -> dict:
    gold = build_gold(workbook, manifest, baseline_source_hash)
    output_path.parent.mkdir(parents=True, exist_ok=True)
    try:
        with output_path.open("x", encoding="utf-8") as stream:
            json.dump(gold, stream, ensure_ascii=False, indent=2)
            stream.write("\n")
    except FileExistsError as exception:
        raise FileExistsError(f"refusing to overwrite frozen gold: {output_path}") from exception
    return gold
