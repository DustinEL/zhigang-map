"""Audit blind resumes against explicitly supplied historical data roots."""

import argparse
import csv
import hashlib
import importlib.util
import json
import re
import sys
from pathlib import Path


SUPPORTED_SUFFIXES = {".docx", ".pdf", ".txt", ".md"}
SHINGLE_SIZE = 5


class AuditError(ValueError):
    pass


def _load_finalizer():
    path = Path(__file__).with_name("finalize-match-blind-intake.py")
    spec = importlib.util.spec_from_file_location("match_blind_finalizer", path)
    if spec is None or spec.loader is None:
        raise AuditError(f"Cannot load finalizer helpers: {path}")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def _read_registry(path):
    try:
        with path.open("r", encoding="utf-8-sig", newline="") as handle:
            return list(csv.DictReader(handle))
    except (OSError, UnicodeError, csv.Error) as error:
        raise AuditError(f"Cannot read registry: {path}") from error


def _normalized_text(text):
    return re.sub(r"[^0-9a-z\u4e00-\u9fff]+", "", text.casefold())


def _shingles(text):
    if not text:
        return set()
    if len(text) <= SHINGLE_SIZE:
        return {text}
    return {
        text[index:index + SHINGLE_SIZE]
        for index in range(len(text) - SHINGLE_SIZE + 1)
    }


def _jaccard(left, right):
    if not left or not right:
        return 0.0
    return len(left & right) / len(left | right)


def _canonical_sha256(value):
    encoded = json.dumps(
        value, ensure_ascii=False, sort_keys=True, separators=(",", ":")
    ).encode("utf-8")
    return hashlib.sha256(encoded).hexdigest()


def _resolve_registered_source(root, source_value, resume_id):
    relative = Path(source_value)
    if relative.is_absolute():
        raise AuditError(f"Resume {resume_id} sourcePath must be relative")
    source = (root / relative).resolve()
    if not source.is_relative_to(root):
        raise AuditError(f"Resume {resume_id} sourcePath escapes root")
    if not source.is_file():
        raise AuditError(f"Resume source does not exist: {source}")
    return source


def _load_current_resumes(intake, root, finalizer):
    registry_path = (intake / "resume-registry.csv").resolve()
    rows = _read_registry(registry_path)
    if not rows:
        raise AuditError("resume-registry.csv contains no resumes")

    resumes = []
    seen_resume_ids = set()
    for row in rows:
        resume_id = str(row.get("resumeId", "")).strip()
        candidate_key = str(row.get("candidateKey", "")).strip()
        duplicate_group = str(row.get("duplicateGroup", "")).strip()
        source_value = str(row.get("sourcePath", "")).strip()
        expected_content = str(row.get("contentSha256", "")).strip().casefold()
        expected_normalized = str(
            row.get("normalizedTextSha256", "")
        ).strip().casefold()
        if not all((resume_id, candidate_key, duplicate_group, source_value,
                    expected_content, expected_normalized)):
            raise AuditError("Intake registry is missing required audit fields")
        if resume_id in seen_resume_ids:
            raise AuditError(f"Duplicate resumeId in intake: {resume_id}")

        source = _resolve_registered_source(root, source_value, resume_id)
        actual_content = finalizer.file_sha256(source)
        if actual_content.casefold() != expected_content:
            raise AuditError(f"Resume {resume_id} contentSha256 does not match source")
        try:
            text = finalizer.extract_text(source)
        except (OSError, ValueError) as error:
            raise AuditError(f"Cannot extract intake resume: {source}") from error
        if not text.strip():
            raise AuditError(f"Resume {resume_id} has no extractable text")
        actual_normalized = finalizer.normalized_text_sha256(text)
        if actual_normalized.casefold() != expected_normalized:
            raise AuditError(
                f"Resume {resume_id} normalizedTextSha256 does not match source"
            )
        normalized = _normalized_text(text)
        resumes.append({
            "resumeId": resume_id,
            "candidateKey": candidate_key,
            "duplicateGroup": duplicate_group,
            "path": source,
            "contentSha256": actual_content,
            "normalizedTextSha256": actual_normalized,
            "shingles": _shingles(normalized),
        })
        seen_resume_ids.add(resume_id)
    return resumes, registry_path


def _history_index(history_roots, current_paths, intake_registry, finalizer):
    history_files = []
    candidate_entries = []
    root_details = []
    seen_files = set()
    seen_registries = set()

    for raw_root in sorted({Path(value).resolve() for value in history_roots},
                           key=lambda value: value.as_posix().casefold()):
        if not raw_root.is_dir():
            raise AuditError(f"History root is not a directory: {raw_root}")
        supported_paths = [
            path.resolve()
            for path in raw_root.rglob("*")
            if path.is_file() and path.suffix.casefold() in SUPPORTED_SUFFIXES
        ]
        if not supported_paths:
            raise AuditError(f"History root has no supported file: {raw_root}")
        snapshots = []
        unsupported_files = []
        for path in sorted(supported_paths,
                           key=lambda value: value.as_posix().casefold()):
            try:
                text = finalizer.extract_text(path)
            except (OSError, ValueError) as error:
                if path.suffix.casefold() in {".txt", ".md"}:
                    unsupported_files.append({
                        "path": path.as_posix(),
                        "reason": type(error).__name__,
                    })
                    continue
                raise AuditError(f"Cannot extract historical resume: {path}") from error
            snapshots.append({
                "path": path.as_posix(),
                "contentSha256": finalizer.file_sha256(path),
                "normalizedTextSha256": finalizer.normalized_text_sha256(text),
            })
        root_details.append({
            "root": raw_root.as_posix(),
            "fileCount": len(snapshots),
            "contentSnapshots": snapshots,
            "unsupportedFiles": unsupported_files,
        })
        supported_history_paths = {
            Path(snapshot["path"]).resolve() for snapshot in snapshots
        }
        for path in sorted(raw_root.rglob("*"),
                           key=lambda value: value.as_posix().casefold()):
            if not path.is_file():
                continue
            resolved = path.resolve()
            if path.name.casefold() == "resume-registry.csv":
                if resolved == intake_registry or resolved in seen_registries:
                    continue
                seen_registries.add(resolved)
                for row in _read_registry(resolved):
                    candidate_key = str(row.get("candidateKey", "")).strip()
                    if candidate_key:
                        candidate_entries.append({
                            "candidateKey": candidate_key,
                            "registryPath": resolved,
                        })
                continue
            if path.suffix.casefold() not in SUPPORTED_SUFFIXES:
                continue
            if resolved in current_paths or resolved in seen_files:
                continue
            if resolved not in supported_history_paths:
                continue
            seen_files.add(resolved)
            try:
                text = finalizer.extract_text(resolved)
            except (OSError, ValueError) as error:
                raise AuditError(f"Cannot extract historical resume: {resolved}") from error
            normalized = _normalized_text(text)
            history_files.append({
                "path": resolved,
                "contentSha256": finalizer.file_sha256(resolved),
                "normalizedTextSha256": finalizer.normalized_text_sha256(text),
                "shingles": _shingles(normalized),
            })
    return history_files, candidate_entries, root_details


def audit(intake, root, history_roots, threshold=0.90):
    """Return a deterministic leakage report for an intake registry."""
    if threshold != 0.90:
        raise AuditError("formal leakage audit threshold must be exactly 0.90")
    intake = Path(intake).resolve()
    root = Path(root).resolve()
    if not intake.is_dir():
        raise AuditError(f"Intake directory does not exist: {intake}")
    if not root.is_dir():
        raise AuditError(f"Workspace root does not exist: {root}")
    if not history_roots:
        raise AuditError("At least one historical root is required")
    resolved_history_roots = sorted(
        {Path(value).resolve() for value in history_roots},
        key=lambda value: value.as_posix().casefold(),
    )

    finalizer = _load_finalizer()
    current, intake_registry = _load_current_resumes(intake, root, finalizer)
    history, historical_candidates, root_details = _history_index(
        resolved_history_roots,
        {item["path"] for item in current},
        intake_registry,
        finalizer,
    )

    findings = []
    # A blind intake must not contain multiple records for the same person or
    # near-duplicate copies of one resume, regardless of historical data.
    for index, resume in enumerate(current):
        for other in current[index + 1:]:
            base = {
                "resumeId": resume["resumeId"],
                "candidateKey": resume["candidateKey"],
                "currentPath": resume["path"].as_posix(),
                "otherResumeId": other["resumeId"],
                "otherPath": other["path"].as_posix(),
            }
            if resume["candidateKey"] == other["candidateKey"]:
                findings.append({**base, "type": "INTERNAL_CANDIDATE_KEY"})
            if resume["duplicateGroup"] == other["duplicateGroup"]:
                findings.append({**base, "type": "INTERNAL_DUPLICATE_GROUP"})
            similarity = _jaccard(resume["shingles"], other["shingles"])
            if similarity >= threshold:
                findings.append({
                    **base, "type": "INTERNAL_NEAR_TEXT",
                    "similarity": round(similarity, 6),
            })
    for resume in current:
        for old in history:
            base = {
                "resumeId": resume["resumeId"],
                "candidateKey": resume["candidateKey"],
                "currentPath": resume["path"].as_posix(),
                "historyPath": old["path"].as_posix(),
            }
            if resume["contentSha256"] == old["contentSha256"]:
                findings.append({**base, "type": "EXACT_BYTE_SHA", "similarity": 1.0})
            elif resume["normalizedTextSha256"] == old["normalizedTextSha256"]:
                findings.append({
                    **base, "type": "NORMALIZED_TEXT_SHA", "similarity": 1.0,
                })
            else:
                similarity = _jaccard(resume["shingles"], old["shingles"])
                if similarity >= threshold:
                    findings.append({
                        **base,
                        "type": "NEAR_TEXT",
                        "similarity": round(similarity, 6),
                    })

        for old_candidate in historical_candidates:
            if resume["candidateKey"] == old_candidate["candidateKey"]:
                findings.append({
                    "type": "CANDIDATE_KEY",
                    "resumeId": resume["resumeId"],
                    "candidateKey": resume["candidateKey"],
                    "currentPath": resume["path"].as_posix(),
                    "historyPath": old_candidate["registryPath"].as_posix(),
                })

    findings.sort(key=lambda item: (
        item["resumeId"], item["type"], item.get("historyPath", ""),
        item.get("currentPath", ""),
    ))
    fingerprint_rows = sorted((
        {
            "resumeId": item["resumeId"],
            "candidateKey": item["candidateKey"],
            "contentSha256": item["contentSha256"],
            "normalizedTextSha256": item["normalizedTextSha256"],
        }
        for item in current
    ), key=lambda item: (item["resumeId"], item["candidateKey"]))
    finding_types = (
        "EXACT_BYTE_SHA", "NORMALIZED_TEXT_SHA", "NEAR_TEXT", "CANDIDATE_KEY",
        "INTERNAL_CANDIDATE_KEY", "INTERNAL_DUPLICATE_GROUP", "INTERNAL_NEAR_TEXT",
    )
    internal_count = sum(item["type"].startswith("INTERNAL_") for item in findings)
    return {
        "schemaVersion": "match-blind-leakage-audit-v1.0",
        "status": "FAIL" if findings else "PASS",
        "intakeFingerprint": _canonical_sha256(fingerprint_rows),
        "historyRoots": [path.as_posix() for path in resolved_history_roots],
        "threshold": threshold,
        "auditProgramSha256": finalizer.file_sha256(Path(__file__).resolve()),
        "historyRootDetails": root_details,
        "counts": {
            "intakeResumes": len(current),
            "historyFiles": len(history),
            "unsupportedHistoryFiles": sum(
                len(detail.get("unsupportedFiles", []))
                for detail in root_details
            ),
            "historyCandidateKeys": len(historical_candidates),
            "findingCount": len(findings),
            "internalFindingCount": internal_count,
            "byType": {
                finding_type: sum(
                    item["type"] == finding_type for item in findings
                )
                for finding_type in finding_types
            },
        },
        "findings": findings,
    }


def parse_args():
    workspace = Path(__file__).resolve().parents[1]
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--intake", type=Path, required=True)
    parser.add_argument("--root", type=Path, default=workspace)
    parser.add_argument(
        "--history-root", type=Path, action="append", required=True,
        dest="history_roots",
    )
    parser.add_argument("--threshold", type=float, default=0.90)
    parser.add_argument("--output", type=Path)
    return parser.parse_args()


def main():
    args = parse_args()
    try:
        report = audit(args.intake, args.root, args.history_roots, args.threshold)
        if args.output:
            if args.output.exists():
                raise AuditError(f"Refusing to overwrite existing report: {args.output}")
            args.output.parent.mkdir(parents=True, exist_ok=True)
            args.output.write_text(
                json.dumps(report, ensure_ascii=False, indent=2) + "\n",
                encoding="utf-8",
            )
        print(
            f"Leakage audit {report['status']}: "
            f"{report['counts']['findingCount']} finding(s)"
        )
        return 0 if report["status"] == "PASS" else 1
    except (AuditError, OSError, json.JSONDecodeError) as error:
        print(f"Error: {error}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
