"""将独立双标与第三方仲裁合并为受控人岗匹配金标。"""

import argparse
import hashlib
import json
import sys
from pathlib import Path


LABELS = {"需先补关键能力", "接近可申请", "可申请实习/校招"}
LABEL_ALIASES = {
    "gap": "需先补关键能力",
    "near": "接近可申请",
    "ready": "可申请实习/校招",
}
IDENTITY_FIELDS = ("resumeId", "roleId")
OUTPUT_IDENTITY_FIELDS = (
    "caseId", "resumeId", "roleId", "roleCode", "roleName", "roleVersion")


class FinalizationError(ValueError):
    pass


def _sha256(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def _read_json(path):
    return json.loads(path.read_text(encoding="utf-8"))


def _by_case(items, source):
    if not isinstance(items, list):
        raise FinalizationError(f"{source} 必须是数组")
    result = {}
    for item in items:
        case_id = str(item.get("caseId", "")).strip()
        if not case_id or case_id in result:
            raise FinalizationError(f"{source} 的 caseId 必须非空且唯一")
        result[case_id] = item
    return result


def _nonempty_strings(values, field, case_id):
    if not isinstance(values, list) or not values or any(
            not str(value).strip() for value in values):
        raise FinalizationError(f"{case_id} 缺少 {field}")
    return [str(value).strip() for value in values]


def _unique(values):
    return list(dict.fromkeys(values))


def build_final_gold(manifest, annotation_a, annotation_b, adjudication,
                     manifest_sha, annotation_a_sha, annotation_b_sha,
                     adjudication_sha):
    bindings = (
        adjudication.get("datasetId") == manifest.get("datasetId"),
        adjudication.get("datasetSha256") == manifest.get("datasetSha256"),
        adjudication.get("manifestSha256") == manifest_sha,
        adjudication.get("annotationASha256") == annotation_a_sha,
        adjudication.get("annotationBSha256") == annotation_b_sha,
    )
    if not all(bindings):
        raise FinalizationError("仲裁文件的数据集或哈希绑定不一致")

    manifest_by_case = _by_case(manifest.get("cases"), "manifest cases")
    a_by_case = _by_case(annotation_a, "annotation-a")
    b_by_case = _by_case(annotation_b, "annotation-b")
    if set(a_by_case) != set(manifest_by_case) or set(b_by_case) != set(manifest_by_case):
        raise FinalizationError("A/B 标注与 manifest 的 caseId 集合不一致")

    disagreement_ids = {
        case_id for case_id in manifest_by_case
        if a_by_case[case_id].get("label") != b_by_case[case_id].get("label")
    }
    adjudication_by_case = _by_case(adjudication.get("items"), "adjudication items")
    if set(adjudication_by_case) != disagreement_ids:
        raise FinalizationError("仲裁项与 A/B 分歧集合不一致")

    gold_cases = []
    for case in manifest["cases"]:
        case_id = case["caseId"]
        left = a_by_case[case_id]
        right = b_by_case[case_id]
        for source, item in (("annotation-a", left), ("annotation-b", right)):
            if any(str(item.get(field)) != str(case.get(field))
                   for field in IDENTITY_FIELDS):
                raise FinalizationError(f"{source} 中 {case_id} 的身份字段不一致")

        output = {field: case.get(field) for field in OUTPUT_IDENTITY_FIELDS}
        if case_id in disagreement_ids:
            resolved = adjudication_by_case[case_id]
            if any(str(resolved.get(field)) != str(case.get(field))
                   for field in IDENTITY_FIELDS):
                raise FinalizationError(f"仲裁项 {case_id} 的身份字段不一致")
            label = LABEL_ALIASES.get(
                str(resolved.get("adjudicatedLabel", "")).strip(),
                str(resolved.get("adjudicatedLabel", "")).strip(),
            )
            evidence = _nonempty_strings(
                resolved.get("adjudicatedEvidence"), "adjudicatedEvidence", case_id)
            reason = str(resolved.get("adjudicationReason", "")).strip()
            if not reason:
                raise FinalizationError(f"{case_id} 缺少 adjudicationReason")
            output.update({
                "label": label,
                "evidence": evidence,
                "reasonCodes": [reason],
                "resolutionSource": "adjudication",
            })
        else:
            label = LABEL_ALIASES.get(
                str(left.get("label", "")).strip(),
                str(left.get("label", "")).strip(),
            )
            evidence = _unique(
                _nonempty_strings(left.get("evidence"), "evidence", case_id)
                + _nonempty_strings(right.get("evidence"), "evidence", case_id))
            reasons = _unique(
                _nonempty_strings(left.get("reasonCodes"), "reasonCodes", case_id)
                + _nonempty_strings(right.get("reasonCodes"), "reasonCodes", case_id))
            output.update({
                "label": label,
                "evidence": evidence,
                "reasonCodes": reasons,
                "resolutionSource": "agreement",
            })
        if label not in LABELS:
            raise FinalizationError(f"{case_id} 的最终类别非法")
        gold_cases.append(output)

    return {
        "schemaVersion": "match-final-gold/v1",
        "datasetId": manifest.get("datasetId"),
        "datasetSha256": manifest.get("datasetSha256"),
        "manifestSha256": manifest_sha,
        "annotationASha256": annotation_a_sha,
        "annotationBSha256": annotation_b_sha,
        "adjudicationSha256": adjudication_sha,
        "caseCount": len(gold_cases),
        "agreementCaseCount": len(gold_cases) - len(disagreement_ids),
        "adjudicatedCaseCount": len(disagreement_ids),
        "undecidableCases": [],
        "cases": gold_cases,
    }


def parse_args():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument("--annotation-a", type=Path, required=True)
    parser.add_argument("--annotation-b", type=Path, required=True)
    parser.add_argument("--adjudication", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    return parser.parse_args()


def run(args):
    if args.output.exists():
        raise FinalizationError(f"拒绝覆盖已存在文件: {args.output}")
    gold = build_final_gold(
        _read_json(args.manifest),
        _read_json(args.annotation_a),
        _read_json(args.annotation_b),
        _read_json(args.adjudication),
        _sha256(args.manifest),
        _sha256(args.annotation_a),
        _sha256(args.annotation_b),
        _sha256(args.adjudication),
    )
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(
        json.dumps(gold, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return 0


def main():
    try:
        return run(parse_args())
    except (OSError, json.JSONDecodeError, FinalizationError) as error:
        print(f"错误: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
