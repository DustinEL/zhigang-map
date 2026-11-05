"""一次性评估冻结的人岗匹配预测与人工仲裁金标。"""

import argparse
import hashlib
import json
import math
import importlib.util
import os
import sys
from collections import Counter, defaultdict
from pathlib import Path


LABELS = ("需先补关键能力", "接近可申请", "可申请实习/校招")
STANDARD_ROLE_IDS = {2, 3, 4, 5, 7, 9, 12}
IDENTITY_FIELDS = ("resumeId", "roleId", "roleCode", "roleName", "roleVersion")


class EvaluationError(ValueError):
    pass


def _load_freeze_module():
    path = Path(__file__).with_name("freeze-match-blind-predictions.py")
    spec = importlib.util.spec_from_file_location("prediction_freeze", path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def _validate_validation_report(report, manifest, gold, manifest_path, gold_path):
    if not isinstance(report, dict) or report.get("passed") is not True:
        raise EvaluationError("double-annotation validation report is not PASS")
    if report.get("datasetId") != manifest.get("datasetId") \
            or report.get("datasetSha256") != manifest.get("datasetSha256"):
        raise EvaluationError("validation report dataset binding does not match manifest")
    gates = report.get("acceptanceGates") or {}
    if gates.get("minimumClassSupport", 0) < 30 \
            or gates.get("minimumResumeCount", 0) < 30 \
            or gates.get("minimumCaseCount", 0) < 210:
        raise EvaluationError("validation report acceptance gates are too low")
    hashes = report.get("hashes") or {}
    if hashes.get("manifestSha256") != _sha256(manifest_path) \
            or hashes.get("goldSha256") != _sha256(gold_path):
        raise EvaluationError("validation report file hash chain is invalid")
    if gold.get("manifestSha256") != _sha256(manifest_path) \
            or gold.get("annotationASha256") != hashes.get("annotationASha256") \
            or gold.get("annotationBSha256") != hashes.get("annotationBSha256"):
        raise EvaluationError("gold annotation hash chain is invalid")


def _canonical_sha256(value):
    return hashlib.sha256(json.dumps(
        value, ensure_ascii=False, sort_keys=True,
        separators=(",", ":")).encode("utf-8")).hexdigest()


def _validate_bindings(manifest, gold, predictions):
    if manifest.get("status") != "FROZEN_FOR_PREDICTION":
        raise EvaluationError("manifest status 必须为 FROZEN_FOR_PREDICTION")
    if manifest.get("containsLabels") is not False:
        raise EvaluationError("manifest containsLabels 必须为 false")
    dataset_id = str(manifest.get("datasetId", "")).strip()
    dataset_sha = str(manifest.get("datasetSha256", "")).strip()
    if not dataset_id or not dataset_sha:
        raise EvaluationError("manifest 缺少 datasetId 或 datasetSha256")
    unhashed = dict(manifest)
    unhashed.pop("datasetSha256", None)
    if _canonical_sha256(unhashed) != dataset_sha:
        raise EvaluationError("manifest datasetSha256 校验失败")
    if not isinstance(gold, dict):
        raise EvaluationError("gold 必须包含 dataset 绑定字段")
    if gold.get("datasetId") != dataset_id or gold.get("datasetSha256") != dataset_sha:
        raise EvaluationError("gold dataset 绑定不一致")
    if not isinstance(predictions, dict):
        raise EvaluationError("predictions 必须包含 dataset 绑定字段")
    if predictions.get("datasetId") != dataset_id or predictions.get("datasetSha256") != dataset_sha:
        raise EvaluationError("predictions dataset 绑定不一致")
    if predictions.get("artifactHashes") != manifest.get("artifacts"):
        raise EvaluationError("predictions artifact 绑定不一致")


def _validate_official_structure(manifest):
    resumes = manifest.get("resumes")
    roles = manifest.get("roles")
    cases = manifest.get("cases")
    if not isinstance(resumes, list) or not isinstance(roles, list) \
            or not isinstance(cases, list) \
            or (len(resumes), len(roles), len(cases)) != (30, 7, 210):
        raise EvaluationError("正式评测必须完整覆盖 30×7=210 对")
    resume_ids = [str(item.get("resumeId", "")).strip() for item in resumes]
    if any(not value for value in resume_ids) or len(set(resume_ids)) != 30:
        raise EvaluationError("正式评测必须包含 30 个非空唯一 resumeId")
    role_by_id = {}
    for role in roles:
        try:
            role_id = int(role.get("roleId"))
        except (TypeError, ValueError):
            raise EvaluationError("正式评测岗位 ID 非法") from None
        if role_id in role_by_id:
            raise EvaluationError("正式评测岗位 ID 必须唯一")
        role_by_id[role_id] = role
    if set(role_by_id) != STANDARD_ROLE_IDS:
        raise EvaluationError("正式评测岗位必须为 2,3,4,5,7,9,12")
    pairs = set()
    case_ids = set()
    for case in cases:
        case_id = str(case.get("caseId", "")).strip()
        resume_id = str(case.get("resumeId", "")).strip()
        try:
            role_id = int(case.get("roleId"))
        except (TypeError, ValueError):
            raise EvaluationError("正式评测 case 岗位 ID 非法") from None
        pair = (resume_id, role_id)
        if not case_id or case_id in case_ids or pair in pairs:
            raise EvaluationError("正式评测 caseId 和候选人岗位对必须唯一")
        if resume_id not in resume_ids or role_id not in role_by_id:
            raise EvaluationError("正式评测 case 引用了未知身份")
        if any(case.get(field) != role_by_id[role_id].get(field)
               for field in ("roleCode", "roleName", "roleVersion")):
            raise EvaluationError("正式评测 case 岗位身份与 roles 不一致")
        case_ids.add(case_id)
        pairs.add(pair)
    expected_pairs = {(resume_id, role_id) for resume_id in resume_ids
                      for role_id in role_by_id}
    if pairs != expected_pairs:
        raise EvaluationError("正式评测 cases 必须完整覆盖 30×7=210 对")


def _validate_thresholds(args):
    values = (args.minimum_accuracy, args.minimum_macro_f1,
              args.minimum_class_recall)
    floors = (0.90, 0.85, 0.80)
    if any(not math.isfinite(value) or value < floor or value > 1.0
           for value, floor in zip(values, floors)):
        raise EvaluationError("正式评测阈值不得低于 0.90/0.85/0.80，且不得高于 1")


def _items_by_case(items, source, label_fields, case_by_id):
    if not isinstance(items, list):
        raise EvaluationError(f"{source} 必须是数组")
    result = {}
    for item in items:
        case_id = str(item.get("caseId", "")).strip()
        label = next((str(item.get(field, "")).strip() for field in label_fields
                      if str(item.get(field, "")).strip()), "")
        if not case_id or case_id in result:
            raise EvaluationError(f"{source} 的 caseId 必须非空且唯一")
        if label not in LABELS:
            raise EvaluationError(f"{source} 中 {case_id} 的类别非法")
        case = case_by_id.get(case_id)
        if case is not None and any(item.get(field) != case.get(field)
                                    for field in IDENTITY_FIELDS):
            raise EvaluationError(f"{source} 中 {case_id} 的身份字段与 manifest 不一致")
        result[case_id] = label
    return result


def _class_metrics(confusion, label):
    true_positive = confusion[label][label]
    support = sum(confusion[label].values())
    predicted = sum(confusion[gold][label] for gold in LABELS)
    precision = true_positive / predicted if predicted else 0.0
    recall = true_positive / support if support else 0.0
    f1 = 2 * precision * recall / (precision + recall) if precision + recall else 0.0
    return {
        "support": support,
        "precision": precision,
        "recall": recall,
        "f1": f1,
    }


def evaluate(manifest, gold, predictions, minimum_accuracy=0.90,
             minimum_macro_f1=0.85, minimum_class_recall=0.80):
    _validate_bindings(manifest, gold, predictions)
    cases = manifest.get("cases")
    if not isinstance(cases, list) or not cases:
        raise EvaluationError("manifest 必须包含非空 cases 数组")
    case_by_id = {}
    for case in cases:
        case_id = str(case.get("caseId", "")).strip()
        if not case_id or case_id in case_by_id:
            raise EvaluationError("manifest 的 caseId 必须非空且唯一")
        case_by_id[case_id] = case

    gold_items = gold.get("cases", gold.get("validCases")) if isinstance(gold, dict) else gold
    prediction_items = predictions.get("predictions") if isinstance(predictions, dict) else predictions
    gold_by_id = _items_by_case(gold_items, "gold", ("label",), case_by_id)
    prediction_by_id = _items_by_case(
        prediction_items, "predictions", ("applicationState", "predicted", "label"),
        case_by_id)
    expected = set(case_by_id)
    if set(gold_by_id) != expected or set(prediction_by_id) != expected:
        raise EvaluationError("manifest、gold 与 predictions 的 caseId 必须完全一致")

    confusion = {gold_label: {predicted: 0 for predicted in LABELS} for gold_label in LABELS}
    per_role_counts = defaultdict(lambda: Counter(total=0, correct=0))
    errors = []
    correct = 0
    for case_id, case in case_by_id.items():
        gold_label = gold_by_id[case_id]
        predicted = prediction_by_id[case_id]
        confusion[gold_label][predicted] += 1
        role_id = str(case.get("roleId"))
        per_role_counts[role_id]["total"] += 1
        if gold_label == predicted:
            correct += 1
            per_role_counts[role_id]["correct"] += 1
        else:
            errors.append({
                "caseId": case_id,
                "resumeId": case.get("resumeId"),
                "roleId": case.get("roleId"),
                "gold": gold_label,
                "predicted": predicted,
            })

    class_metrics = {label: _class_metrics(confusion, label) for label in LABELS}
    accuracy = correct / len(case_by_id)
    macro_f1 = sum(metrics["f1"] for metrics in class_metrics.values()) / len(LABELS)
    failures = []
    if accuracy < minimum_accuracy:
        failures.append(f"accuracy<{minimum_accuracy:.2f}")
    if macro_f1 < minimum_macro_f1:
        failures.append(f"macroF1<{minimum_macro_f1:.2f}")
    for label, metrics in class_metrics.items():
        if metrics["recall"] < minimum_class_recall:
            failures.append(f"recall[{label}]<{minimum_class_recall:.2f}")

    return {
        "passed": not failures,
        "caseCount": len(case_by_id),
        "correct": correct,
        "accuracy": accuracy,
        "macroF1": macro_f1,
        "thresholds": {
            "minimumAccuracy": minimum_accuracy,
            "minimumMacroF1": minimum_macro_f1,
            "minimumClassRecall": minimum_class_recall,
        },
        "classMetrics": class_metrics,
        "confusionMatrix": confusion,
        "perRole": {
            role_id: {
                "caseCount": counts["total"],
                "correct": counts["correct"],
                "accuracy": counts["correct"] / counts["total"],
            }
            for role_id, counts in sorted(per_role_counts.items(), key=lambda item: int(item[0]))
        },
        "failures": failures,
        "errors": errors,
    }


def _read_json(path):
    return json.loads(path.read_text(encoding="utf-8"))


def _sha256(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def parse_args():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument("--gold", type=Path, required=True)
    parser.add_argument("--predictions", type=Path, required=True)
    parser.add_argument("--report", type=Path, required=True)
    parser.add_argument("--validation-report", type=Path, required=True)
    parser.add_argument("--prediction-freeze", type=Path, required=True)
    parser.add_argument("--freeze-key-env", default="MATCH_BLIND_FREEZE_KEY")
    parser.add_argument("--minimum-accuracy", type=float, default=0.90)
    parser.add_argument("--minimum-macro-f1", type=float, default=0.85)
    parser.add_argument("--minimum-class-recall", type=float, default=0.80)
    return parser.parse_args()


def run(args):
    manifest = _read_json(args.manifest)
    gold = _read_json(args.gold)
    predictions = _read_json(args.predictions)
    expected_manifest_hash = _sha256(args.manifest)
    if predictions.get("manifestFileSha256") != expected_manifest_hash:
        raise EvaluationError("predictions manifestFileSha256 绑定不一致")
    validation_report = _read_json(args.validation_report)
    _validate_validation_report(
        validation_report, manifest, gold, args.manifest, args.gold)
    freeze_record = _read_json(args.prediction_freeze)
    try:
        _load_freeze_module().verify_freeze(
            freeze_record, args.predictions, args.manifest,
            os.environ.get(args.freeze_key_env, ""))
    except ValueError as error:
        raise EvaluationError(f"prediction freeze verification failed: {error}") from error
    _validate_official_structure(manifest)
    _validate_thresholds(args)
    report = evaluate(
        manifest, gold, predictions,
        args.minimum_accuracy, args.minimum_macro_f1, args.minimum_class_recall)
    report["hashes"] = {
        "manifestSha256": _sha256(args.manifest),
        "goldSha256": _sha256(args.gold),
        "predictionsSha256": _sha256(args.predictions),
        "validationReportSha256": _sha256(args.validation_report),
        "predictionFreezeSha256": _sha256(args.prediction_freeze),
    }
    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.report.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return 0 if report["passed"] else 2


def main():
    try:
        return run(parse_args())
    except (OSError, json.JSONDecodeError, EvaluationError) as error:
        print(f"错误: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
