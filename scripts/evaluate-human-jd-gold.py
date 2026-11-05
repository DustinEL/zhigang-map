from __future__ import annotations

import argparse
import base64
import hashlib
import json
import random
import re
import urllib.request
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path

from openpyxl import load_workbook


PROJECT_ROOT = Path(__file__).resolve().parents[1]
DEFAULT_GOLD = PROJECT_ROOT / "test-data" / "jd-extraction" / "JD人工复核金标100条-冻结版.xlsx"
DEFAULT_REPORT = PROJECT_ROOT / "test-data" / "evaluation" / "jd-human-gold-evaluation-report.json"
DEFAULT_CACHE = PROJECT_ROOT / "test-data" / "evaluation" / "jd-human-gold-predictions.json"
DEFAULT_SKILL_POLICY = PROJECT_ROOT / "backend" / "src" / "main" / "resources" / "analysis" / "jd-skill-policy-v2.json"
RESPONSIBILITY_THRESHOLD = 0.62
BOOTSTRAP_SEED = 20260813
DEFAULT_ACCEPTANCE_THRESHOLDS = {
    "combinedF1": 92.0,
    "skillF1": 92.0,
    "skillPrecision": 92.0,
    "skillRecall": 90.0,
    "responsibilityF1": 92.0,
    "combinedLower": 90.0,
    "skillLower": 90.0,
    "responsibilityLower": 90.0,
}


@dataclass(frozen=True)
class Counts:
    tp: int
    fp: int
    fn: int

    def __add__(self, other: "Counts") -> "Counts":
        return Counts(self.tp + other.tp, self.fp + other.fp, self.fn + other.fn)


def normalize_text(value: str) -> str:
    return re.sub(r"\s+", " ", str(value or "").strip()).lower()


def load_skill_canonical_aliases(path: Path = DEFAULT_SKILL_POLICY) -> dict[str, str]:
    policy = json.loads(path.read_text(encoding="utf-8"))
    result: dict[str, str] = {}
    for canonical, aliases in policy.get("aliases", {}).items():
        normalized_canonical = normalize_text(canonical)
        for value in [canonical, *aliases]:
            normalized_value = normalize_text(value)
            existing = result.get(normalized_value)
            if existing is not None and existing != normalized_canonical:
                raise ValueError(f"技能别名映射冲突：{value}")
            result[normalized_value] = normalized_canonical
    return result


SKILL_CANONICAL_ALIASES = load_skill_canonical_aliases()


def normalize_skill(value: str) -> str:
    normalized = normalize_text(value)
    return SKILL_CANONICAL_ALIASES.get(normalized, normalized)


def normalize_responsibility(value: str) -> str:
    value = re.sub(r"^[\d一二三四五六七八九十、.．()（）\s]+", "", str(value or ""))
    value = re.sub(r"[。；;\s]+$", "", value)
    return re.sub(r"\s+", "", value)


def character_bigrams(value: str) -> set[str]:
    compact = re.sub(r"\s+", "", value)
    return {compact[index : index + 2] for index in range(max(0, len(compact) - 1))}


def responsibility_similarity(left: str, right: str) -> float:
    left = normalize_responsibility(left)
    right = normalize_responsibility(right)
    if not left or not right:
        return 0.0
    if left in right or right in left:
        return min(len(left), len(right)) / max(len(left), len(right))
    left_tokens = character_bigrams(left)
    right_tokens = character_bigrams(right)
    union = left_tokens | right_tokens
    return len(left_tokens & right_tokens) / len(union) if union else 0.0


def exact_set_counts(expected: list[str], predicted: list[str], normalizer) -> Counts:
    expected_set = {normalizer(value) for value in expected if normalizer(value)}
    predicted_set = {normalizer(value) for value in predicted if normalizer(value)}
    return Counts(
        tp=len(expected_set & predicted_set),
        fp=len(predicted_set - expected_set),
        fn=len(expected_set - predicted_set),
    )


def match_responsibilities(expected: list[str], predicted: list[str]) -> Counts:
    normalized_predicted = [normalize_responsibility(value) for value in predicted if normalize_responsibility(value)]
    used: set[int] = set()
    true_positive = 0
    for expected_value in expected:
        best_index = -1
        best_score = 0.0
        for index, predicted_value in enumerate(normalized_predicted):
            if index in used:
                continue
            score = responsibility_similarity(expected_value, predicted_value)
            if score > best_score:
                best_index = index
                best_score = score
        if best_index >= 0 and best_score >= RESPONSIBILITY_THRESHOLD:
            used.add(best_index)
            true_positive += 1
    return Counts(true_positive, len(normalized_predicted) - len(used), len(expected) - true_positive)


def metric_from_counts(tp: int, fp: int, fn: int) -> dict:
    precision = tp / (tp + fp) if tp + fp else 0.0
    recall = tp / (tp + fn) if tp + fn else 0.0
    f1 = 2 * precision * recall / (precision + recall) if precision + recall else 0.0
    return {
        "tp": tp,
        "fp": fp,
        "fn": fn,
        "precision": round(precision * 100, 2),
        "recall": round(recall * 100, 2),
        "f1": round(f1 * 100, 2),
    }


def parse_pipe(value: str, separator: str) -> list[str]:
    return [item.strip() for item in str(value or "").split(separator) if item.strip()]


def load_gold(path: Path, expected_count: int = 100, require_reviewed: bool = False) -> list[dict]:
    workbook = load_workbook(path, read_only=True, data_only=False)
    sheet = workbook["JD人工标注"]
    cases = []
    for row in sheet.iter_rows(min_row=2, values_only=True):
        if require_reviewed:
            if str(row[9] or "").strip() != "已复核" or str(row[20] or "").strip() != "通过":
                raise AssertionError(f"样本 {row[0]} 尚未完成复核")
            if not str(row[21] or "").strip() or not str(row[22] or "").strip():
                raise AssertionError(f"样本 {row[0]} 缺少复核人或复核日期")
        cases.append({
            "sampleNumber": int(row[0]),
            "recordId": int(row[1]),
            "title": str(row[10]),
            "skills": parse_pipe(row[14], "|"),
            "responsibilities": parse_pipe(row[15], "||"),
            "inputText": f"岗位名称：{row[6]}\n{row[8]}",
            "contentHash": row[23],
        })
    workbook.close()
    if len(cases) != expected_count:
        raise AssertionError(f"金标数量不是{expected_count}条：{len(cases)}")
    return cases


class ApiClient:
    def __init__(self, base_url: str, username: str = "", password: str = ""):
        self.base_url = base_url.rstrip("/")
        self.opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor())
        self.csrf_token = ""
        self.authorization = ""
        if username and password:
            token = base64.b64encode(f"{username}:{password}".encode("utf-8")).decode("ascii")
            self.authorization = f"Basic {token}"

    def headers(self, mutating: bool = False) -> dict[str, str]:
        headers = {"Accept": "application/json"}
        if self.authorization:
            headers["Authorization"] = self.authorization
        if mutating:
            headers["Content-Type"] = "application/json; charset=utf-8"
            if self.csrf_token:
                headers["X-XSRF-TOKEN"] = self.csrf_token
        return headers

    def load_csrf(self) -> None:
        request = urllib.request.Request(f"{self.base_url}/api/security/csrf", headers=self.headers())
        with self.opener.open(request, timeout=15) as response:
            payload = json.loads(response.read().decode("utf-8"))
        self.csrf_token = ((payload.get("data") or {}).get("token") or "").strip()

    def post(self, path: str, body: dict) -> dict:
        if not self.csrf_token:
            self.load_csrf()
        request = urllib.request.Request(
            f"{self.base_url}{path}",
            data=json.dumps(body, ensure_ascii=False).encode("utf-8"),
            headers=self.headers(mutating=True),
            method="POST",
        )
        with self.opener.open(request, timeout=300) as response:
            payload = json.loads(response.read().decode("utf-8"))
        if payload.get("code") != "OK" and payload.get("success") is not True:
            raise RuntimeError(f"API失败：{payload}")
        return payload["data"]


def sample_f1(counts: Counts) -> float:
    return metric_from_counts(counts.tp, counts.fp, counts.fn)["f1"] / 100


def bootstrap_mean(values: list[float], samples: int = 2000) -> dict:
    randomizer = random.Random(BOOTSTRAP_SEED)
    means = []
    for _ in range(samples):
        selected = [values[randomizer.randrange(len(values))] for _ in values]
        means.append(sum(selected) / len(selected))
    means.sort()
    return {
        "mean": round(sum(values) / len(values) * 100, 2),
        "lower": round(means[int(samples * 0.025)] * 100, 2),
        "upper": round(means[min(samples - 1, int(samples * 0.975))] * 100, 2),
        "bootstrapSamples": samples,
        "seed": BOOTSTRAP_SEED,
    }


def bootstrap_micro_metric(
    case_counts: list[Counts],
    samples: int = 10000,
    seed: int = BOOTSTRAP_SEED,
) -> dict:
    if not case_counts:
        raise ValueError("bootstrap 至少需要一条样本计数")
    randomizer = random.Random(seed)
    values = []
    for _ in range(samples):
        total = Counts(0, 0, 0)
        for _ in case_counts:
            total += case_counts[randomizer.randrange(len(case_counts))]
        values.append(metric_from_counts(total.tp, total.fp, total.fn)["f1"])
    values.sort()
    total = sum(case_counts, Counts(0, 0, 0))
    return {
        "pointEstimate": metric_from_counts(total.tp, total.fp, total.fn)["f1"],
        "lower": round(values[int(samples * 0.05)], 2),
        "upper": round(values[min(samples - 1, int(samples * 0.95))], 2),
        "confidence": "one-sided-95-percent-lower-bound",
        "bootstrapSamples": samples,
        "seed": seed,
    }


def evaluate_acceptance(result: dict, thresholds: dict | None = None) -> dict:
    thresholds = {**DEFAULT_ACCEPTANCE_THRESHOLDS, **(thresholds or {})}
    combined = result["primarySkillsResponsibilities"]
    skill = result["fields"]["skill"]
    responsibility = result["fields"]["responsibility"]
    intervals = result["microConfidenceIntervals95"]
    actual = {
        "combinedF1": combined["f1"],
        "skillF1": skill["f1"],
        "skillPrecision": skill["precision"],
        "skillRecall": skill["recall"],
        "responsibilityF1": responsibility["f1"],
        "combinedLower": intervals["primarySkillsResponsibilities"]["lower"],
        "skillLower": intervals["skill"]["lower"],
        "responsibilityLower": intervals["responsibility"]["lower"],
    }
    gates = {
        name: {
            "actual": actual[name],
            "required": required,
            "passed": actual[name] >= required,
        }
        for name, required in thresholds.items()
    }
    failed = [name for name, gate in gates.items() if not gate["passed"]]
    return {
        "passed": not failed,
        "failedGates": failed,
        "gates": gates,
    }


def validate_run_mode(
    official: bool,
    fresh: bool,
    require_reviewed: bool,
    runtime_fingerprint: str,
    artifact_sha256: str,
) -> None:
    if official and not fresh:
        raise ValueError("正式评测必须启用 --fresh，禁止复用历史预测缓存")
    if official and not require_reviewed:
        raise ValueError("正式评测必须启用 --require-reviewed，禁止使用未完成逐条复核的金标")
    if official and not runtime_fingerprint.strip():
        raise ValueError("正式评测必须提供 --runtime-fingerprint")
    if official and not artifact_sha256.strip():
        raise ValueError("正式评测必须提供 --artifact-sha256")


def validate_official_evidence(
    official: bool,
    expected_count: int,
    manifest_path: Path | None,
    leakage_report_path: Path | None,
    annotation_evidence_path: Path | None,
) -> dict | None:
    if not official:
        return None
    if expected_count < 200:
        raise ValueError("正式评测至少 200 条独立样本")
    paths = {
        "manifest": manifest_path,
        "leakageReport": leakage_report_path,
        "annotationEvidence": annotation_evidence_path,
    }
    for name, path in paths.items():
        if path is None or not path.is_file():
            raise ValueError(f"正式评测缺少 {name} 证明文件")

    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    leakage = json.loads(leakage_report_path.read_text(encoding="utf-8"))
    annotation = json.loads(annotation_evidence_path.read_text(encoding="utf-8"))
    if manifest.get("sampleCount") != expected_count:
        raise ValueError("正式评测 manifest 样本数与 --expected-count 不一致")
    if leakage.get("passed") is not True or leakage.get("candidateCount") != expected_count or leakage.get("findings"):
        raise ValueError("正式评测泄漏审计未通过或样本数不一致")
    if annotation.get("sampleCount") != expected_count or annotation.get("doubleAnnotated") is not True:
        raise ValueError("正式评测必须完成全量双人独立标注")
    annotators = annotation.get("annotators")
    adjudicator = annotation.get("adjudicator")
    if not isinstance(annotators, list) or len(annotators) != 2 or len(set(annotators)) != 2:
        raise ValueError("正式评测必须由两名不同标注员独立标注")
    if not adjudicator or adjudicator in annotators:
        raise ValueError("正式评测必须由第三人完成分歧裁决")
    if annotation.get("allDisagreementsAdjudicated") is not True:
        raise ValueError("正式评测仍有未裁决的标注分歧")
    if annotation.get("predictionFrozenBeforeGold") is not True:
        raise ValueError("正式评测必须在金标解封前冻结预测")
    return {
        "sampleCount": expected_count,
        "manifestSha256": sha256(manifest_path),
        "leakageReportSha256": sha256(leakage_report_path),
        "annotationEvidenceSha256": sha256(annotation_evidence_path),
    }


def evaluation_status(official: bool, metrics_passed: bool) -> dict:
    if official:
        return {
            "gateStatus": "PASS_CLAIM_90" if metrics_passed else "FAIL_CLAIM_90",
            "passed": metrics_passed,
        }
    return {
        "gateStatus": "PASS_METRICS_NON_OFFICIAL" if metrics_passed else "FAIL_METRICS_NON_OFFICIAL",
        "passed": False,
    }


def prediction_cache_key(case: dict) -> str:
    identity = {
        "sampleNumber": case["sampleNumber"],
        "recordId": case["recordId"],
        "inputSha256": hashlib.sha256(case["inputText"].encode("utf-8")).hexdigest(),
    }
    encoded = json.dumps(identity, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
    return hashlib.sha256(encoded.encode("utf-8")).hexdigest()


def load_prediction_cache(
    path: Path,
    dataset_sha256: str,
    allow_legacy: bool,
    expected_binding: dict | None = None,
) -> tuple[dict, bool]:
    if not path.exists():
        return {
            "schemaVersion": "2.0.0",
            "datasetSha256": dataset_sha256,
            "binding": expected_binding,
            "predictions": {},
        }, False
    payload = json.loads(path.read_text(encoding="utf-8"))
    if payload.get("schemaVersion") != "2.0.0":
        if allow_legacy:
            return payload, True
        raise ValueError("正式评测禁止使用旧版预测缓存")
    if payload.get("datasetSha256") != dataset_sha256:
        raise ValueError("预测缓存的数据集哈希不一致")
    if expected_binding is not None and payload.get("binding") != expected_binding:
        raise ValueError("预测缓存的运行时或制品绑定不一致")
    if not isinstance(payload.get("predictions"), dict):
        raise ValueError("预测缓存缺少 predictions 对象")
    return payload, False


def evaluate(
    cases: list[dict],
    client: ApiClient,
    cache_path: Path,
    dataset_sha256: str,
    allow_legacy_cache: bool = True,
    cache_binding: dict | None = None,
) -> dict:
    cache, legacy_cache = load_prediction_cache(
        cache_path, dataset_sha256, allow_legacy_cache, expected_binding=cache_binding)
    predictions = cache if legacy_cache else cache["predictions"]
    totals = {"title": Counts(0, 0, 0), "skill": Counts(0, 0, 0), "responsibility": Counts(0, 0, 0)}
    primary_totals = {"skill": Counts(0, 0, 0), "responsibility": Counts(0, 0, 0)}
    errors = []
    sample_scores = []
    case_counts = {"skill": [], "responsibility": [], "primarySkillsResponsibilities": []}
    for index, case in enumerate(cases, 1):
        cache_key = str(case["sampleNumber"]) if legacy_cache else prediction_cache_key(case)
        cached = predictions.get(cache_key)
        predicted = cached if legacy_cache else (cached or {}).get("prediction")
        prediction_source = "cache" if predicted is not None else "api"
        if predicted is None:
            predicted = client.post("/api/job-descriptions/extractions", {"documentId": case["recordId"], "text": case["inputText"]})
            if legacy_cache:
                predictions[cache_key] = predicted
            else:
                predictions[cache_key] = {
                    "inputSha256": hashlib.sha256(case["inputText"].encode("utf-8")).hexdigest(),
                    "prediction": predicted,
                }
            cache_path.parent.mkdir(parents=True, exist_ok=True)
            cache_path.write_text(json.dumps(cache, ensure_ascii=False, indent=2), encoding="utf-8")
        predicted_skills = [fact["normalizedValue"] for fact in predicted.get("facts", []) if fact.get("type") == "SKILL"]
        field_counts = {
            "title": exact_set_counts([case["title"]], [predicted.get("title", "")], normalize_text),
            "skill": exact_set_counts(case["skills"], predicted_skills, normalize_skill),
            "responsibility": match_responsibilities(case["responsibilities"], predicted.get("responsibilities", [])),
        }
        combined = Counts(0, 0, 0)
        for field, counts in field_counts.items():
            totals[field] = totals[field] + counts
            combined = combined + counts
        primary_combined = field_counts["skill"] + field_counts["responsibility"]
        case_counts["skill"].append(field_counts["skill"])
        case_counts["responsibility"].append(field_counts["responsibility"])
        case_counts["primarySkillsResponsibilities"].append(primary_combined)
        primary_totals["skill"] = primary_totals["skill"] + field_counts["skill"]
        primary_totals["responsibility"] = primary_totals["responsibility"] + field_counts["responsibility"]
        sample_scores.append(sample_f1(primary_combined))
        if combined.fp or combined.fn:
            errors.append({
                "sampleNumber": case["sampleNumber"],
                "recordId": case["recordId"],
                "expected": {"title": case["title"], "skills": case["skills"], "responsibilities": case["responsibilities"]},
                "predicted": {"title": predicted.get("title"), "skills": predicted_skills, "responsibilities": predicted.get("responsibilities", [])},
                "counts": {field: counts.__dict__ for field, counts in field_counts.items()},
                "modelMode": predicted.get("modelMode"),
                "warnings": predicted.get("warnings", []),
            })
        print(f"[{index}/{len(cases)}] sample={case['sampleNumber']} f1={sample_scores[-1] * 100:.2f} source={prediction_source} saved={len(predictions)}", flush=True)

    overall = sum(totals.values(), Counts(0, 0, 0))
    primary_overall = sum(primary_totals.values(), Counts(0, 0, 0))
    return {
        "overall": metric_from_counts(overall.tp, overall.fp, overall.fn),
        "primarySkillsResponsibilities": metric_from_counts(primary_overall.tp, primary_overall.fp, primary_overall.fn),
        "fields": {field: metric_from_counts(counts.tp, counts.fp, counts.fn) for field, counts in totals.items()},
        "macroSampleF1ConfidenceInterval95": bootstrap_mean(sample_scores),
        "microConfidenceIntervals95": {
            field: bootstrap_micro_metric(counts)
            for field, counts in case_counts.items()
        },
        "errorSampleCount": len(errors),
        "errors": errors,
    }


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def ensure_fresh_cache(path: Path, fresh: bool) -> None:
    if fresh and path.exists():
        raise FileExistsError(f"全新缓存模式禁止复用已有文件：{path}")


def local_credentials() -> tuple[str, str]:
    values = {}
    env_path = PROJECT_ROOT / ".env.local"
    if env_path.exists():
        for line in env_path.read_text(encoding="utf-8").splitlines():
            stripped = line.strip()
            if stripped and not stripped.startswith("#") and "=" in stripped:
                name, value = stripped.split("=", 1)
                values[name.strip()] = value.strip()
    return values.get("PROFESSIONAL_USERNAME", ""), values.get("PROFESSIONAL_PASSWORD", "")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--base-url", default="http://127.0.0.1:8080")
    parser.add_argument("--gold", type=Path, default=DEFAULT_GOLD)
    parser.add_argument("--report", type=Path, default=DEFAULT_REPORT)
    parser.add_argument("--cache", type=Path, default=DEFAULT_CACHE)
    parser.add_argument("--fresh", action="store_true", help="要求预测缓存不存在，防止误用旧结果")
    parser.add_argument("--expected-count", type=int, default=100)
    parser.add_argument("--require-reviewed", action="store_true")
    parser.add_argument("--official", action="store_true", help="启用严格验收门禁，失败时返回非零退出码")
    parser.add_argument("--runtime-fingerprint", default="")
    parser.add_argument("--artifact-sha256", default="")
    parser.add_argument("--manifest", type=Path)
    parser.add_argument("--leakage-report", type=Path)
    parser.add_argument("--annotation-evidence", type=Path)
    args = parser.parse_args()
    validate_run_mode(
        args.official,
        args.fresh,
        args.require_reviewed,
        args.runtime_fingerprint,
        args.artifact_sha256,
    )
    official_evidence = validate_official_evidence(
        args.official,
        args.expected_count,
        args.manifest,
        args.leakage_report,
        args.annotation_evidence,
    )
    ensure_fresh_cache(args.cache, args.fresh)
    cases = load_gold(args.gold, expected_count=args.expected_count, require_reviewed=args.require_reviewed)
    username, password = local_credentials()
    gold_sha256 = sha256(args.gold)
    metric_policy = json.dumps(
        {
            "responsibilityThreshold": RESPONSIBILITY_THRESHOLD,
            "skillCanonicalAliases": SKILL_CANONICAL_ALIASES,
            "acceptance": DEFAULT_ACCEPTANCE_THRESHOLDS,
        },
        sort_keys=True,
        separators=(",", ":"),
    )
    metric_policy_sha256 = hashlib.sha256(metric_policy.encode("utf-8")).hexdigest()
    cache_binding = {
        "runtimeFingerprint": args.runtime_fingerprint or None,
        "artifactSha256": args.artifact_sha256 or None,
        "metricPolicySha256": metric_policy_sha256,
    } if args.official else None
    result = evaluate(
        cases,
        ApiClient(args.base_url, username, password),
        args.cache,
        gold_sha256,
        allow_legacy_cache=not args.official,
        cache_binding=cache_binding,
    )
    report = {
        "schemaVersion": "2.0.0",
        "generatedAt": datetime.now(timezone.utc).isoformat(),
        "official": args.official,
        "dataset": args.gold.stem,
        "sampleCount": len(cases),
        "goldSha256": gold_sha256,
        "predictionSha256": sha256(args.cache),
        "artifactProvenance": {
            "runtimeFingerprint": args.runtime_fingerprint or None,
            "artifactSha256": args.artifact_sha256 or None,
            "metricPolicySha256": metric_policy_sha256,
        },
        "officialEvidence": official_evidence,
        "inputPolicy": "岗位名称：采集岗位标题 + JD原文",
        "annotationPolicy": "AI辅助预标注，经韩帅文逐条对照原文审核确认",
        "metricDefinition": "岗位名称精确匹配；技能按冻结别名表归一化后精确匹配；职责使用字符二元组Jaccard相似度>=0.62的一对一匹配；总体为实体级微平均F1",
        "acceptanceThresholds": DEFAULT_ACCEPTANCE_THRESHOLDS,
        **result,
    }
    report["acceptance"] = evaluate_acceptance(report)
    report.update(evaluation_status(args.official, report["acceptance"]["passed"]))
    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.report.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps({"report": str(args.report), "overall": report["overall"], "passed": report["passed"]}, ensure_ascii=False, indent=2))
    if args.official and not report["passed"]:
        raise SystemExit(2)


if __name__ == "__main__":
    main()
