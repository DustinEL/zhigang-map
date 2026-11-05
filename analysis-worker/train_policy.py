from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
from typing import Any

import numpy as np
from sklearn.linear_model import LogisticRegression
from sklearn.metrics import f1_score, precision_score


FEATURES = [
    "companySupport",
    "sourceSupport",
    "windowGrowth",
    "titleNovelty",
    "responsibilityDifference",
    "skillCombinationDifference",
]


def load_samples(path: Path) -> list[dict[str, Any]]:
    content = path.read_text(encoding="utf-8")
    if path.suffix.lower() == ".jsonl":
        samples = [json.loads(line) for line in content.splitlines() if line.strip()]
    else:
        samples = json.loads(content)
    if not isinstance(samples, list) or not samples:
        raise ValueError(f"{path} 必须包含非空样本数组")
    return samples


def dataset_hash(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def require_fields(samples: list[dict[str, Any]], fields: list[str], split: str) -> None:
    missing = [index for index, sample in enumerate(samples) if any(field not in sample for field in fields)]
    if missing:
        raise ValueError(f"{split} 缺少字段 {fields}，首个无效样本索引为 {missing[0]}")


def best_f1_threshold(labels: np.ndarray, probabilities: np.ndarray) -> float:
    thresholds = np.unique(np.concatenate(([0.0], probabilities, [1.0])))
    return float(max(thresholds, key=lambda threshold: (f1_score(labels, probabilities >= threshold), threshold)))


def publish_threshold(labels: np.ndarray, probabilities: np.ndarray, minimum_precision: float) -> float:
    thresholds = np.unique(np.concatenate((probabilities, [1.0])))
    valid = [threshold for threshold in thresholds
             if np.any(probabilities >= threshold)
             and precision_score(labels, probabilities >= threshold, zero_division=0) >= minimum_precision]
    if not valid:
        raise ValueError(f"验证集无法达到发布精确率门槛 {minimum_precision:.2f}")
    return float(min(valid))


def variant_threshold(samples: list[dict[str, Any]]) -> float:
    similarities = np.asarray([sample["nearestRoleSimilarity"] for sample in samples], dtype=float)
    labels = np.asarray([sample["existingVariant"] for sample in samples], dtype=int)
    thresholds = np.unique(np.concatenate(([0.0], similarities, [1.0])))
    return float(max(thresholds, key=lambda threshold: (f1_score(labels, similarities >= threshold), threshold)))


def calibrate(development: list[dict[str, Any]], validation: list[dict[str, Any]],
              base_policy: dict[str, Any], minimum_publish_precision: float) -> dict[str, Any]:
    require_fields(development, FEATURES + ["label"], "development")
    require_fields(validation, FEATURES + ["label", "publishable", "existingVariant", "nearestRoleSimilarity"], "validation")
    development_matrix = np.asarray([[sample[feature] for feature in FEATURES] for sample in development], dtype=float)
    development_labels = np.asarray([sample["label"] for sample in development], dtype=int)
    if len(np.unique(development_labels)) != 2:
        raise ValueError("development 必须同时包含新岗位正例和非新岗位负例")
    model = LogisticRegression(random_state=42, max_iter=2000).fit(development_matrix, development_labels)
    validation_matrix = np.asarray([[sample[feature] for feature in FEATURES] for sample in validation], dtype=float)
    probabilities = model.predict_proba(validation_matrix)[:, 1]
    candidate_labels = np.asarray([sample["label"] for sample in validation], dtype=int)
    publish_labels = np.asarray([sample["publishable"] for sample in validation], dtype=int)
    policy = dict(base_policy)
    policy.update({
        "algorithm": "logistic-regression",
        "intercept": float(model.intercept_[0]),
        "weights": dict(zip(FEATURES, map(float, model.coef_[0]), strict=True)),
        "candidateThreshold": best_f1_threshold(candidate_labels, probabilities),
        "publishThreshold": publish_threshold(publish_labels, probabilities, minimum_publish_precision),
        "existingRoleSimilarityThreshold": variant_threshold(validation),
    })
    return policy


def main() -> None:
    parser = argparse.ArgumentParser(description="使用开发集训练、验证集校准新岗位发现策略")
    parser.add_argument("development", type=Path)
    parser.add_argument("validation", type=Path)
    parser.add_argument("base_policy", type=Path)
    parser.add_argument("output", type=Path)
    parser.add_argument("--version", required=True)
    parser.add_argument("--minimum-publish-precision", type=float, default=0.80)
    arguments = parser.parse_args()
    development = load_samples(arguments.development)
    validation = load_samples(arguments.validation)
    base_policy = json.loads(arguments.base_policy.read_text(encoding="utf-8"))
    policy = calibrate(development, validation, base_policy, arguments.minimum_publish_precision)
    policy["version"] = arguments.version
    policy["calibrationDataset"] = (
        f"development:sha256:{dataset_hash(arguments.development)};"
        f"validation:sha256:{dataset_hash(arguments.validation)}"
    )
    arguments.output.write_text(json.dumps(policy, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
