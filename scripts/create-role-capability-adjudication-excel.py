"""Create a Chinese Excel workbook for resolving role-card review disagreements."""

import argparse
import csv
from pathlib import Path

from openpyxl import Workbook
from openpyxl.formatting.rule import FormulaRule
from openpyxl.styles import Alignment, Border, Font, PatternFill, Side
from openpyxl.worksheet.datavalidation import DataValidation


DECISION_LABELS = {
    "ACCEPT": "通过",
    "MODIFY": "修改",
    "REJECT": "驳回",
}
ITEM_LABELS = {
    "CORE": "核心能力",
    "TRANSFERABLE": "可迁移技能",
    "BONUS": "加分技能",
}
DECISION_FIELDS = (
    "decision",
    "proposedValues",
    "proposedNearEvidenceLevel",
    "proposedReadyEvidenceLevel",
    "proposedBlocking",
)


def read_csv(path):
    with path.open(encoding="utf-8-sig", newline="") as handle:
        return list(csv.DictReader(handle))


def decision_key(row):
    return tuple(row.get(field, "").strip() for field in DECISION_FIELDS)


def display_level(value):
    value = value.strip()
    descriptions = {
        "0": "L0｜仅关键词或无法定位",
        "1": "L1｜课程学习或技能声明",
        "2": "L2｜项目中实际使用",
        "3": "L3｜有成果、测试或量化效果",
        "4": "L4｜生产或正式工作中使用",
    }
    return descriptions.get(value, value or "不适用")


def display_blocking(value):
    return {"true": "是", "false": "否", "": "不修改"}.get(value.strip(), value)


def effective_value(row, proposed_field, current_field, formatter=lambda value: value):
    proposed = row.get(proposed_field, "").strip()
    current = row.get(current_field, "").strip()
    return formatter(proposed or current)


def build_workbook(rows_a, rows_b, destination):
    disagreements = [
        (left, right)
        for left, right in zip(rows_a, rows_b)
        if decision_key(left) != decision_key(right)
    ]

    workbook = Workbook()
    sheet = workbook.active
    sheet.title = "分歧仲裁"
    instructions = workbook.create_sheet("填写说明")
    program = workbook.create_sheet("_程序数据")

    navy = "1F4E78"
    pale_blue = "D9EAF7"
    pale_green = "E2F0D9"
    pale_yellow = "FFF2CC"
    pale_red = "FCE4D6"
    white = "FFFFFF"
    border = Border(*(Side(style="thin", color="D9E1F2") for _ in range(4)))

    sheet.merge_cells("A1:U1")
    sheet["A1"] = "岗位能力卡分歧仲裁表"
    sheet["A1"].font = Font(size=16, bold=True, color=white)
    sheet["A1"].fill = PatternFill("solid", fgColor=navy)
    sheet["A1"].alignment = Alignment(horizontal="center")
    sheet["A2"] = "仲裁人姓名"
    sheet["D2"] = "仲裁日期"
    sheet["G2"] = "待仲裁项"
    sheet["H2"] = len(disagreements)
    sheet["J2"] = "原则"
    sheet["K2"] = "只判断哪套要求更符合岗位实际；不需要理解程序"

    headers = [
        "序号", "岗位名称", "审核项目", "能力项", "当前内容",
        "当前接近要求", "当前可申请要求", "当前是否阻断",
        "A 的结论", "A 的最终接近要求", "A 的最终可申请要求", "A 的最终阻断", "A 的原因",
        "B 的结论", "B 的最终接近要求", "B 的最终可申请要求", "B 的最终阻断", "B 的原因",
        "仲裁选择", "自定义修改（仅选自定义时填）", "仲裁理由（必填）",
    ]
    header_row = 5
    for column, header in enumerate(headers, 1):
        cell = sheet.cell(header_row, column, header)
        cell.font = Font(bold=True, color=white)
        cell.fill = PatternFill("solid", fgColor=navy)
        cell.alignment = Alignment(horizontal="center", vertical="center", wrap_text=True)
        cell.border = border

    for index, (left, right) in enumerate(disagreements, 1):
        row = header_row + index
        values = [
            index,
            left["roleName"],
            ITEM_LABELS.get(left["itemType"], left["itemType"]),
            left["capabilityId"],
            left["currentValues"].replace(" | ", "、"),
            display_level(left["nearEvidenceLevel"]),
            display_level(left["readyEvidenceLevel"]),
            display_blocking(left["blocking"]),
            DECISION_LABELS.get(left["decision"], left["decision"]),
            effective_value(left, "proposedNearEvidenceLevel", "nearEvidenceLevel", display_level),
            effective_value(left, "proposedReadyEvidenceLevel", "readyEvidenceLevel", display_level),
            effective_value(left, "proposedBlocking", "blocking", display_blocking),
            left["evidenceOrReason"],
            DECISION_LABELS.get(right["decision"], right["decision"]),
            effective_value(right, "proposedNearEvidenceLevel", "nearEvidenceLevel", display_level),
            effective_value(right, "proposedReadyEvidenceLevel", "readyEvidenceLevel", display_level),
            effective_value(right, "proposedBlocking", "blocking", display_blocking),
            right["evidenceOrReason"],
            "",
            "",
            "",
        ]
        for column, value in enumerate(values, 1):
            cell = sheet.cell(row, column, value)
            cell.alignment = Alignment(vertical="top", wrap_text=True)
            cell.border = border
            if 9 <= column <= 13:
                cell.fill = PatternFill("solid", fgColor=pale_blue)
            elif 14 <= column <= 18:
                cell.fill = PatternFill("solid", fgColor=pale_green)
            elif column >= 19:
                cell.fill = PatternFill("solid", fgColor=pale_yellow)

    last_row = header_row + len(disagreements)
    choice_validation = DataValidation(
        type="list", formula1='"采用 A,采用 B,自定义"', allow_blank=True
    )
    sheet.add_data_validation(choice_validation)
    choice_validation.add(f"S{header_row + 1}:S{last_row}")
    sheet.conditional_formatting.add(
        f"S{header_row + 1}:U{last_row}",
        FormulaRule(formula=[f'$S{header_row + 1}="自定义"'], fill=PatternFill("solid", fgColor=pale_red)),
    )
    sheet.freeze_panes = "A6"
    sheet.auto_filter.ref = f"A{header_row}:U{last_row}"
    sheet.row_dimensions[1].height = 28
    sheet.row_dimensions[header_row].height = 48
    widths = {
        "A": 7, "B": 24, "C": 14, "D": 18, "E": 34,
        "F": 23, "G": 23, "H": 14, "I": 11, "J": 23, "K": 23, "L": 14, "M": 48,
        "N": 11, "O": 23, "P": 23, "Q": 14, "R": 48, "S": 14, "T": 34, "U": 42,
    }
    for column, width in widths.items():
        sheet.column_dimensions[column].width = width

    instructions.column_dimensions["A"].width = 110
    notes = [
        "填写说明",
        "1. 这份表只包含 A、B 两位审核人意见不同的项目。",
        "2. 先比较蓝色的 A 方案和绿色的 B 方案，再在黄色列选择“采用 A”“采用 B”或“自定义”。",
        "3. 选择“自定义”时，在下一列写清最终要求；采用 A/B 时不用重复填写具体等级。",
        "4. 每项都要填写一句仲裁理由，例如：该岗位校招阶段有项目实际使用即可，因此采用 A。",
        "5. L1=学习过或简历声明；L2=在项目中实际使用；L3=有可运行成果、测试报告或量化结果；L4=生产或正式工作中使用。",
        "6. “阻断”表示缺少该能力时不能进入“可申请”；加分能力不能抵消阻断能力缺失。",
        "7. 仲裁人必须不同于韩帅文和张栋勋。",
    ]
    for row, note in enumerate(notes, 1):
        cell = instructions.cell(row, 1, note)
        cell.alignment = Alignment(wrap_text=True, vertical="top")
        cell.font = Font(size=15 if row == 1 else 11, bold=row == 1)
    instructions.row_dimensions[1].height = 28

    program_headers = [
        "excelRow", "roleId", "roleCode", "roleName", "cardVersion", "itemType",
        "capabilityId", "currentValues", "aDecision", "bDecision",
    ]
    program.append(program_headers)
    for index, (left, right) in enumerate(disagreements, 1):
        program.append([
            header_row + index, left["roleId"], left["roleCode"], left["roleName"],
            left["cardVersion"], left["itemType"], left["capabilityId"],
            left["currentValues"], left["decision"], right["decision"],
        ])
    program.sheet_state = "hidden"

    destination.parent.mkdir(parents=True, exist_ok=True)
    workbook.save(destination)
    return len(disagreements)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("package", type=Path)
    parser.add_argument("destination", type=Path)
    args = parser.parse_args()
    rows_a = read_csv(args.package / "reviewer-a.csv")
    rows_b = read_csv(args.package / "reviewer-b.csv")
    if len(rows_a) != len(rows_b):
        raise SystemExit("A/B 审核结果数量不一致")
    count = build_workbook(rows_a, rows_b, args.destination)
    print(f"已生成 {count} 条分歧的仲裁表: {args.destination.resolve()}")


if __name__ == "__main__":
    main()
