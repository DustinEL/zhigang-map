import argparse
import hashlib
import json
import re
from pathlib import Path
from xml.etree import ElementTree
from zipfile import ZipFile


AMBIGUOUS_CANONICALS = {"Cursor"}
SOURCE_MARKERS = ("公开来源", "仅用于网站独立盲测")
HEADINGS = {
    "skills": "skills",
    "technical skills": "skills",
    "technical summary": "skills",
    "projects": "projects",
    "project experience": "projects",
    "experience": "experience",
    "professional experience": "experience",
    "education": "education",
}


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def paragraphs(docx_path: Path) -> list[str]:
    with ZipFile(docx_path) as archive:
        root = ElementTree.fromstring(archive.read("word/document.xml"))
    return [
        "".join(node.text or "" for node in paragraph.iter() if node.tag.endswith("}t")).strip()
        for paragraph in root.iter()
        if paragraph.tag.endswith("}p")
    ]


def is_source_metadata(paragraph: str) -> bool:
    lower = paragraph.lower()
    return any(marker in paragraph for marker in SOURCE_MARKERS) or (
        "github.com/" in lower and len(paragraph) < 180
    )


def exact_pattern(skill: str) -> re.Pattern[str]:
    escaped = re.escape(skill)
    if skill and skill[0].isascii() and skill[-1].isascii():
        return re.compile(r"(?<![A-Za-z0-9-])" + escaped + r"(?![A-Za-z0-9-])", re.IGNORECASE)
    return re.compile(escaped, re.IGNORECASE)


def acceptable_context(skill: str, paragraph: str) -> bool:
    lower = paragraph.lower()
    if skill == "Postman" and "postman inc" in lower:
        return False
    if skill == "GitHub" and ("view github" in lower or "linkedin" in lower and "stackoverflow" in lower):
        return False
    return True


def locate(skill: str, values: list[str]) -> tuple[int, str, str] | None:
    if skill in AMBIGUOUS_CANONICALS:
        return None
    pattern = exact_pattern(skill)
    section = "other"
    for number, paragraph in enumerate(values, 1):
        heading = HEADINGS.get(paragraph.strip().lower().rstrip(":"))
        if heading:
            section = heading
        if not paragraph or is_source_metadata(paragraph) or not acceptable_context(skill, paragraph):
            continue
        match = pattern.search(paragraph)
        if match:
            return number, section, paragraph
    return None


def convert(repository_root: Path, source: Path, report_path: Path, target: Path) -> None:
    data = json.loads(source.read_text(encoding="utf-8"))
    report = json.loads(report_path.read_text(encoding="utf-8"))
    if data.get("version") != "resume-skill-regression-gold-v3":
        raise ValueError("source must be resume-skill-regression-gold-v3")
    if report.get("runKind") != "REGRESSION_TUNING":
        raise ValueError("report must be REGRESSION_TUNING")

    report_samples = {sample["sampleId"]: sample for sample in report["samples"]}
    added = []
    rejected = []
    for sample in data["samples"]:
        result = report_samples[sample["sampleId"]]
        values = paragraphs(repository_root / sample["docxPath"])
        existing = set(sample["skills"])
        for skill in result["unexpectedSkills"]:
            if skill in existing:
                continue
            location = locate(skill, values)
            if location is None:
                rejected.append({"sampleId": sample["sampleId"], "skill": skill})
                continue
            paragraph_number, section, quote = location
            sample["skills"].append(skill)
            sample["evidence"].append({
                "canonicalSkill": skill,
                "surfaceForm": skill,
                "section": section,
                "locator": f"P{paragraph_number:03d}",
                "exactQuote": quote,
                "ontologySource": "POST_RUN_ADJUDICATION",
                "uncertainty": "",
                "annotatorNote": (
                    "Prediction-informed correction: canonical name appears verbatim in original DOCX body."
                ),
            })
            existing.add(skill)
            added.append({"sampleId": sample["sampleId"], "skill": skill})

        if len(sample["skills"]) != len(set(sample["skills"])):
            raise ValueError(f"duplicate skills in sample {sample['sampleId']}")
        if set(sample["skills"]) != {item["canonicalSkill"] for item in sample["evidence"]}:
            raise ValueError(f"evidence mismatch in sample {sample['sampleId']}")

    data["version"] = "resume-skill-regression-gold-v4"
    data["supersedes"] = "resume-skill-regression-gold-v3"
    data["provenance"]["postRunEvidenceAdjudication"] = {
        "sourceReport": str(report_path.as_posix()),
        "sourceReportSha256": sha256(report_path),
        "predictionInformed": True,
        "policy": (
            "Added only unexpected canonical names found verbatim in a non-metadata DOCX paragraph; "
            "ambiguous aliases and source-only matches were rejected."
        ),
        "added": added,
        "rejected": rejected,
    }
    with target.open("x", encoding="utf-8", newline="\n") as output:
        json.dump(data, output, ensure_ascii=False, indent=2)
        output.write("\n")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--repository-root", type=Path, required=True)
    parser.add_argument("--source", type=Path, required=True)
    parser.add_argument("--report", type=Path, required=True)
    parser.add_argument("--target", type=Path, required=True)
    args = parser.parse_args()
    convert(args.repository_root.resolve(), args.source.resolve(), args.report.resolve(), args.target.resolve())


if __name__ == "__main__":
    main()
