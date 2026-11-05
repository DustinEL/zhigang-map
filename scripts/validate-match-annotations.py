"""校验人岗匹配双人标注、候选人隔离和类别支持度。"""

import argparse
import hashlib
import json
import sys
from collections import Counter, defaultdict
from pathlib import Path


LABELS = ("gap", "near", "ready")
LABEL_ALIASES = {
    "需先补关键能力": "gap",
    "接近可申请": "near",
    "可申请实习/校招": "ready",
}
STANDARD_ROLE_IDS = {2, 3, 4, 5, 7, 9, 12}


class ValidationError(ValueError):
    pass


def normalize_label(label: str) -> str:
    if label in LABELS:
        return label
    if label in LABEL_ALIASES:
        return LABEL_ALIASES[label]
    raise ValidationError("标注包含非法类别")


def annotation_agreement(labels_a, labels_b):
    if len(labels_a) != len(labels_b) or not labels_a:
        raise ValidationError("两份标注必须包含相同数量且非空的样本")
    labels_a = [normalize_label(label) for label in labels_a]
    labels_b = [normalize_label(label) for label in labels_b]

    count = len(labels_a)
    exact = sum(a == b for a, b in zip(labels_a, labels_b)) / count
    index = {label: position for position, label in enumerate(LABELS)}
    max_distance = len(LABELS) - 1
    observed_disagreement = sum(
        ((index[a] - index[b]) / max_distance) ** 2
        for a, b in zip(labels_a, labels_b)
    ) / count
    counts_a = Counter(labels_a)
    counts_b = Counter(labels_b)
    expected_disagreement = sum(
        counts_a[a] * counts_b[b] * ((index[a] - index[b]) / max_distance) ** 2
        for a in LABELS
        for b in LABELS
    ) / (count * count)
    kappa = 1.0 if expected_disagreement == 0 and observed_disagreement == 0 else (
        0.0 if expected_disagreement == 0 else 1 - observed_disagreement / expected_disagreement
    )
    return {"exactAgreement": exact, "weightedKappa": kappa, "sampleCount": count}


def validate_manifest_isolation(manifest, strict=False, acceptance_split="blind"):
    resumes = manifest.get("resumes")
    cases = manifest.get("cases")
    if not isinstance(resumes, list) or not isinstance(cases, list):
        raise ValidationError("manifest 必须包含 resumes 和 cases 数组")

    resume_by_id = {}
    identity_splits = defaultdict(set)
    for resume in resumes:
        resume_id = str(resume.get("resumeId", "")).strip()
        split = str(resume.get("split", "")).strip()
        if not resume_id or resume_id in resume_by_id:
            raise ValidationError("resumeId 必须非空且唯一")
        if not split:
            raise ValidationError(f"简历 {resume_id} 缺少 split")
        if strict and split != acceptance_split:
            raise ValidationError(f"最终集合包含非 {acceptance_split} 简历: {resume_id}")
        if strict and not str(resume.get("candidateKey", "")).strip():
            raise ValidationError(f"简历 {resume_id} 缺少 candidateKey")
        if strict and not str(resume.get("contentSha256", "")).strip():
            raise ValidationError(f"简历 {resume_id} 缺少 contentSha256")
        resume_by_id[resume_id] = resume
        for field in ("duplicateGroup", "contentSha256"):
            value = str(resume.get(field, "")).strip()
            if value:
                identity_splits[(field, value)].add(split)

    for (field, value), splits in identity_splits.items():
        if len(splits) > 1:
            raise ValidationError(f"{field}={value} 跨集合出现: {sorted(splits)}")

    seen_cases = set()
    seen_pairs = set()
    role_ids = set()
    for case in cases:
        case_id = str(case.get("caseId", "")).strip()
        resume_id = str(case.get("resumeId", "")).strip()
        role_id = case.get("roleId")
        if not case_id or case_id in seen_cases:
            raise ValidationError("caseId 必须非空且唯一")
        if resume_id not in resume_by_id:
            raise ValidationError(f"case {case_id} 引用了未知简历")
        pair = (resume_id, role_id)
        if pair in seen_pairs:
            raise ValidationError(f"候选人-岗位对重复: {pair}")
        seen_cases.add(case_id)
        seen_pairs.add(pair)
        try:
            role_ids.add(int(role_id))
        except (TypeError, ValueError):
            raise ValidationError(f"case {case_id} 的 roleId 非法") from None
    if strict:
        if len(resumes) != 30 or len(cases) != 210:
            raise ValidationError("最终集合必须恰好包含 30 份简历和 210 个 case")
        if role_ids != STANDARD_ROLE_IDS:
            raise ValidationError("最终集合必须使用标准岗位 2,3,4,5,7,9,12")
        expected_pairs = {(resume["resumeId"], role_id)
                          for resume in resumes for role_id in STANDARD_ROLE_IDS}
        if seen_pairs != expected_pairs:
            raise ValidationError("最终集合必须完整覆盖 30×7 标准岗位矩阵")
    return {"resumeCount": len(resumes), "caseCount": len(cases),
            "roleIds": sorted(role_ids)}


def _validate_annotation_identity(items, source, case_by_id):
    for item in items:
        case_id = str(item.get("caseId", "")).strip()
        expected = case_by_id.get(case_id)
        if expected is None:
            continue
        for field in ("resumeId", "roleId"):
            if field not in item or str(item.get(field)).strip() == "":
                raise ValidationError(f"{source} 中 {case_id} 缺少身份字段 {field}")
            if field in item and str(item.get(field)) != str(expected.get(field)):
                raise ValidationError(f"{source} 中 {case_id} 的身份字段 {field} 与 manifest 不一致")


def _labels_by_case(items, expected_case_ids, source, strict=False, case_by_id=None):
    if not isinstance(items, list):
        raise ValidationError(f"{source} 必须是数组")
    if strict and case_by_id is not None:
        _validate_annotation_identity(items, source, case_by_id)
    result = {}
    for item in items:
        case_id = str(item.get("caseId", "")).strip()
        label = str(item.get("label", "")).strip()
        if not case_id or case_id in result:
            raise ValidationError(f"{source} 的 caseId 必须非空且唯一")
        try:
            label = normalize_label(label)
        except ValidationError:
            raise ValidationError(f"{source} 中 {case_id} 的非法类别") from None
        if strict:
            evidence = item.get("evidence")
            reason_codes = item.get("reasonCodes")
            if not isinstance(evidence, list) or not evidence or any(
                    not str(value).strip() for value in evidence):
                raise ValidationError(f"{source} 中 {case_id} 缺少 evidence")
            if not isinstance(reason_codes, list) or not reason_codes or any(
                    not str(value).strip() for value in reason_codes):
                raise ValidationError(f"{source} 中 {case_id} 缺少 reasonCodes")
        result[case_id] = label
    if set(result) != expected_case_ids:
        raise ValidationError(f"{source} 与 manifest 的 caseId 不一致")
    return result


def validate(manifest, annotation_a, annotation_b, gold=None, minimum_support=30,
             minimum_resumes=30, minimum_cases=210, strict=False,
             annotator_a="", annotator_b=""):
    if strict:
        if not annotator_a.strip() or not annotator_b.strip() or annotator_a.strip() == annotator_b.strip():
            raise ValidationError("两名标注者必须填写且身份不同")
        if gold is None:
            raise ValidationError("最终验收必须提供受控 gold")
        if minimum_support < 30 or minimum_resumes < 30 or minimum_cases < 210:
            raise ValidationError("minimum acceptance gates cannot be lower than 30/30/210")
        if gold.get("datasetId") != manifest.get("datasetId") \
                or gold.get("datasetSha256") != manifest.get("datasetSha256"):
            raise ValidationError("gold dataset binding does not match manifest")
    isolation = validate_manifest_isolation(manifest, strict=strict)
    case_ids = {str(case["caseId"]) for case in manifest["cases"]}
    case_by_id = {str(case["caseId"]): case for case in manifest["cases"]}
    labels_a = _labels_by_case(annotation_a, case_ids, "annotation-a", strict=strict, case_by_id=case_by_id)
    labels_b = _labels_by_case(annotation_b, case_ids, "annotation-b", strict=strict, case_by_id=case_by_id)
    ordered_ids = sorted(case_ids)
    agreement = annotation_agreement(
        [labels_a[case_id] for case_id in ordered_ids],
        [labels_b[case_id] for case_id in ordered_ids],
    )

    support = {label: 0 for label in LABELS}
    if gold is not None:
        undecidable = gold.get("undecidableCases", [])
        if undecidable:
            raise ValidationError("最终金标不得包含 undecidableCases")
        gold_cases = gold.get("cases")
        if gold_cases is None:
            gold_cases = gold.get("validCases")
        gold_labels = _labels_by_case(gold_cases, case_ids, "gold", strict=strict)
        support.update(Counter(gold_labels.values()))

    failures = []
    if agreement["exactAgreement"] < 0.85:
        failures.append("exactAgreement<0.85")
    if agreement["weightedKappa"] < 0.80:
        failures.append("weightedKappa<0.80")
    if isolation["resumeCount"] < minimum_resumes:
        failures.append(f"resumeCount<{minimum_resumes}")
    if isolation["caseCount"] < minimum_cases:
        failures.append(f"caseCount<{minimum_cases}")
    if gold is not None:
        failures.extend(
            f"support[{label}]<{minimum_support}"
            for label in LABELS
            if support[label] < minimum_support
        )
    return {
        "passed": not failures,
        **agreement,
        **isolation,
        "classSupport": support,
        "failures": failures,
    }


def _read_json(path):
    return json.loads(path.read_text(encoding="utf-8"))


def _sha256(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def parse_args():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument("--annotation-a", type=Path, required=True)
    parser.add_argument("--annotation-b", type=Path, required=True)
    parser.add_argument("--gold", type=Path)
    parser.add_argument("--minimum-support", type=int, default=30)
    parser.add_argument("--minimum-resumes", type=int, default=30)
    parser.add_argument("--minimum-cases", type=int, default=210)
    parser.add_argument("--annotator-a", required=True)
    parser.add_argument("--annotator-b", required=True)
    parser.add_argument("--report", type=Path, required=True)
    return parser.parse_args()


def run(args):
    manifest = _read_json(args.manifest)
    annotation_a = _read_json(args.annotation_a)
    annotation_b = _read_json(args.annotation_b)
    gold = _read_json(args.gold) if args.gold else None
    if _sha256(args.annotation_a) == _sha256(args.annotation_b):
        raise ValidationError("两份独立标注不得来自同一个文件")
    report = validate(
        manifest, annotation_a, annotation_b, gold,
        args.minimum_support, args.minimum_resumes, args.minimum_cases,
        strict=True, annotator_a=args.annotator_a, annotator_b=args.annotator_b,
    )
    report["hashes"] = {
        "manifestSha256": _sha256(args.manifest),
        "annotationASha256": _sha256(args.annotation_a),
        "annotationBSha256": _sha256(args.annotation_b),
        "goldSha256": _sha256(args.gold) if args.gold else None,
    }
    report["acceptanceGates"] = {
        "minimumExactAgreement": 0.85,
        "minimumWeightedKappa": 0.80,
        "minimumClassSupport": args.minimum_support,
        "minimumResumeCount": args.minimum_resumes,
        "minimumCaseCount": args.minimum_cases,
    }
    report["datasetId"] = manifest.get("datasetId")
    report["datasetSha256"] = manifest.get("datasetSha256")
    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.report.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return 0 if report["passed"] else 2


def main():
    try:
        return run(parse_args())
    except (OSError, json.JSONDecodeError, ValidationError) as error:
        print(f"错误: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
