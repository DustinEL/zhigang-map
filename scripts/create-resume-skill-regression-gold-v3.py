import argparse
import json
from collections import OrderedDict
from pathlib import Path


CANONICAL_RENAMES = {
    "Visual Studio Code": "VS Code",
    "Transformers": "Transformer",
    "Microservices": "微服务",
    "Object-Oriented Programming": "面向对象",
    "ELK Stack": "ELK",
    "Infrastructure as Code": "IaC",
    "Deep Learning": "深度学习",
    "Fine-tuning": "微调",
    "Multi-agent Systems": "多智能体",
    "Data Structures & Algorithms": "数据结构与算法",
    "Machine Learning": "机器学习",
    "Feature Engineering": "特征工程",
    "Random Forest": "随机森林",
    "Network Security": "网络安全",
    "Responsive Design": "响应式设计",
    "Amazon S3": "AWS S3",
    "Amazon DynamoDB": "DynamoDB",
    "Amazon CloudWatch": "CloudWatch",
    "Adobe Photoshop": "Photoshop",
}


def merge_text(left: str, right: str) -> str:
    values = [value for value in (left, right) if value]
    return " | ".join(dict.fromkeys(values))


def merge_evidence(existing: dict, incoming: dict) -> dict:
    merged = dict(existing)
    for field in ("surfaceForm", "section", "locator", "exactQuote", "ontologySource",
                  "uncertainty", "annotatorNote"):
        merged[field] = merge_text(str(existing.get(field, "")), str(incoming.get(field, "")))
    return merged


def convert(source: Path, target: Path) -> None:
    data = json.loads(source.read_text(encoding="utf-8"))
    if data.get("version") != "resume-skill-regression-gold-v2":
        raise ValueError("source must be resume-skill-regression-gold-v2")
    if len(data.get("samples", [])) != 30:
        raise ValueError("source must contain exactly 30 samples")

    data["version"] = "resume-skill-regression-gold-v3"
    data["supersedes"] = "resume-skill-regression-gold-v2"
    data["provenance"]["canonicalCorrectionsAfterFirstPrediction"] = True
    data["provenance"]["canonicalCorrectionReason"] = (
        "Post-run-001 normalization-only alignment with established production canonical names; "
        "no evidence-backed skill was added or removed."
    )
    data["provenance"]["canonicalRenames"] = CANONICAL_RENAMES

    for sample in data["samples"]:
        normalized_skills = list(OrderedDict.fromkeys(
            CANONICAL_RENAMES.get(skill, skill) for skill in sample["skills"]
        ))
        evidence_by_skill: OrderedDict[str, dict] = OrderedDict()
        for evidence in sample["evidence"]:
            converted = dict(evidence)
            canonical = CANONICAL_RENAMES.get(converted["canonicalSkill"], converted["canonicalSkill"])
            converted["canonicalSkill"] = canonical
            if canonical in evidence_by_skill:
                evidence_by_skill[canonical] = merge_evidence(evidence_by_skill[canonical], converted)
            else:
                evidence_by_skill[canonical] = converted

        if len(normalized_skills) != len(set(normalized_skills)):
            raise ValueError(f"duplicate skills remain in sample {sample['sampleId']}")
        missing_evidence = set(normalized_skills) - set(evidence_by_skill)
        extra_evidence = set(evidence_by_skill) - set(normalized_skills)
        if missing_evidence or extra_evidence:
            raise ValueError(
                f"evidence mismatch in sample {sample['sampleId']}: "
                f"missing={sorted(missing_evidence)}, extra={sorted(extra_evidence)}"
            )
        sample["skills"] = normalized_skills
        sample["evidence"] = list(evidence_by_skill.values())

    with target.open("x", encoding="utf-8", newline="\n") as output:
        json.dump(data, output, ensure_ascii=False, indent=2)
        output.write("\n")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("source", type=Path)
    parser.add_argument("target", type=Path)
    args = parser.parse_args()
    convert(args.source, args.target)


if __name__ == "__main__":
    main()
