"""将中文岗位能力卡审核 Excel 导出为现有门禁可读取的 CSV。"""

import argparse
import csv
from datetime import date, datetime
from pathlib import Path

from openpyxl import load_workbook


FIELDS = [
    "roleId", "roleCode", "roleName", "cardVersion", "itemType", "capabilityId",
    "currentValues", "nearEvidenceLevel", "readyEvidenceLevel", "blocking",
    "sourceReferences", "decision", "proposedValues", "proposedNearEvidenceLevel",
    "proposedReadyEvidenceLevel", "proposedBlocking", "evidenceOrReason", "comments",
    "reviewerId", "reviewedAt",
]
DECISIONS = {"通过": "ACCEPT", "修改": "MODIFY", "驳回": "REJECT"}


class ValidationError(ValueError):
    pass


def text(value):
    return "" if value is None else str(value).strip()


def level(value):
    value = text(value).upper()
    if not value:
        return ""
    if value not in {"L0", "L1", "L2", "L3", "L4"}:
        raise ValidationError(f"证据等级无效: {value}")
    return value[1:]


def reviewed_at(value):
    if isinstance(value, (datetime, date)):
        return value.isoformat()
    return text(value)


def export_review(workbook_path, destination):
    workbook = load_workbook(workbook_path, data_only=False)
    review = workbook["审核表"]
    metadata = workbook["_程序数据"]
    reviewer = text(review["B2"].value)
    reviewed = reviewed_at(review["E2"].value)
    if not reviewer or not reviewed:
        raise ValidationError("审核未完成：请填写审核人姓名和审核日期")

    program_headers = [text(cell.value) for cell in metadata[1]][:12]
    rows = []
    incomplete = []
    for values in metadata.iter_rows(min_row=2, max_col=12, values_only=True):
        program = dict(zip(program_headers, values))
        excel_row = int(program["excelRow"])
        chinese_decision = text(review.cell(excel_row, 9).value)
        if chinese_decision not in DECISIONS:
            incomplete.append(str(excel_row))
            continue
        decision = DECISIONS[chinese_decision]
        proposed_values = text(review.cell(excel_row, 10).value)
        proposed_near = level(review.cell(excel_row, 11).value)
        proposed_ready = level(review.cell(excel_row, 12).value)
        proposed_blocking_text = text(review.cell(excel_row, 13).value)
        proposed_blocking = {"是": "true", "否": "false", "": ""}.get(proposed_blocking_text)
        if proposed_blocking is None:
            raise ValidationError(f"第 {excel_row} 行的阻断选择无效")
        reason = text(review.cell(excel_row, 14).value)
        if decision in {"MODIFY", "REJECT"} and not reason:
            raise ValidationError(f"第 {excel_row} 行选择了修改或驳回，请填写审核依据 / 原因")
        if decision == "MODIFY" and not any((proposed_values, proposed_near, proposed_ready, proposed_blocking)):
            raise ValidationError(f"第 {excel_row} 行选择了修改，但没有填写任何修改建议")

        rows.append({
            "roleId": text(program["roleId"]),
            "roleCode": text(program["roleCode"]),
            "roleName": text(program["roleName"]),
            "cardVersion": text(program["cardVersion"]),
            "itemType": text(program["itemType"]),
            "capabilityId": text(program["capabilityId"]),
            "currentValues": text(program["currentValues"]),
            "nearEvidenceLevel": text(program["nearEvidenceLevel"]),
            "readyEvidenceLevel": text(program["readyEvidenceLevel"]),
            "blocking": text(program["blocking"]),
            "sourceReferences": text(program["sourceReferences"]),
            "decision": decision,
            "proposedValues": proposed_values.replace("、", " | "),
            "proposedNearEvidenceLevel": proposed_near,
            "proposedReadyEvidenceLevel": proposed_ready,
            "proposedBlocking": proposed_blocking,
            "evidenceOrReason": reason or "人工审核确认通过",
            "comments": text(review.cell(excel_row, 15).value),
            "reviewerId": reviewer,
            "reviewedAt": reviewed,
        })
    if incomplete:
        raise ValidationError("审核未完成：以下 Excel 行尚未选择结论：" + "、".join(incomplete))

    destination.parent.mkdir(parents=True, exist_ok=True)
    with destination.open("w", encoding="utf-8-sig", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=FIELDS)
        writer.writeheader()
        writer.writerows(rows)
    return rows


def parse_args():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("workbook", type=Path)
    parser.add_argument("destination", type=Path)
    return parser.parse_args()


def main():
    args = parse_args()
    try:
        rows = export_review(args.workbook, args.destination)
    except (OSError, ValidationError) as error:
        print(f"错误: {error}")
        return 1
    print(f"已导出 {len(rows)} 条审核结果: {args.destination.resolve()}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
