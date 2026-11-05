from __future__ import annotations

import argparse
import json
import subprocess
from pathlib import Path, PurePosixPath
from zipfile import ZipFile

from resume_skill_blind_baseline import sha256_file, validate_baseline


def read_json(path: Path) -> dict:
    return json.loads(path.read_text(encoding="utf-8"))


def _safe_extract(archive_path: Path, target: Path) -> None:
    with ZipFile(archive_path) as archive:
        for member in archive.infolist():
            parts = PurePosixPath(member.filename).parts
            if member.filename.startswith(("/", "\\")) or ".." in parts:
                raise ValueError(f"unsafe source capsule entry: {member.filename}")
        archive.extractall(target)


def prepare_run(project_root: Path, baseline_path: Path, manifest_path: Path, gold_path: Path,
                report_path: Path, log_path: Path) -> dict:
    if report_path.exists() or log_path.exists():
        raise FileExistsError("refusing to overwrite first-run report or console log")
    baseline = read_json(baseline_path)
    manifest = read_json(manifest_path)
    gold = read_json(gold_path)
    if not (baseline.get("datasetSha256") == manifest.get("datasetSha256") == gold.get("datasetSha256")) \
            or baseline.get("sourceTreeSha256") != gold.get("baselineSourceTreeSha256"):
        raise ValueError("baseline/manifest/gold hash binding mismatch")
    if baseline.get("manifestSha256") and sha256_file(manifest_path) != baseline["manifestSha256"]:
        raise ValueError("manifest file hash differs from baseline")
    baseline_failures = validate_baseline(project_root, baseline_path)
    if baseline_failures:
        raise ValueError(f"baseline integrity failures: {baseline_failures}")

    capsule_path = project_root / baseline["sourceCapsule"]["path"]
    if not capsule_path.is_file() or sha256_file(capsule_path) != baseline["sourceCapsule"]["sha256"]:
        raise ValueError("source capsule hash mismatch")
    run_root = project_root / "backend" / "target" / f"blind-baseline-run-{baseline['sourceTreeSha256'][:12]}"
    if run_root.exists():
        raise FileExistsError(f"refusing to reuse baseline run directory: {run_root}")
    run_root.mkdir(parents=True)
    _safe_extract(capsule_path, run_root)
    extracted_backend = run_root / "backend"
    if not (extracted_backend / "pom.xml").is_file():
        raise ValueError("source capsule does not contain backend/pom.xml")
    report_path.parent.mkdir(parents=True, exist_ok=True)
    return {
        "baseline": baseline,
        "backend": extracted_backend,
        "report": report_path.resolve(),
        "log": log_path.resolve(),
        "manifest": manifest_path.resolve(),
        "gold": gold_path.resolve(),
        "baselinePath": baseline_path.resolve(),
        "repositoryRoot": project_root.resolve(),
    }


def execute_run(prepared: dict) -> tuple[int, dict | None]:
    backend = prepared["backend"]
    arguments = [
        str(backend / "mvnw.cmd"),
        "-Dtest=ResumeSkillBlindThirtyEvaluationTest",
        f"-Dblind.repositoryRoot={prepared['repositoryRoot']}",
        f"-Dblind.gold={prepared['gold']}",
        f"-Dblind.manifest={prepared['manifest']}",
        f"-Dblind.baseline={prepared['baselinePath']}",
        f"-Dblind.report={prepared['report']}",
        "test",
    ]
    command_line = subprocess.list2cmdline(arguments)
    with prepared["log"].open("x", encoding="utf-8") as log:
        result = subprocess.run(["cmd.exe", "/d", "/s", "/c", command_line], cwd=backend,
                                stdout=log, stderr=subprocess.STDOUT, text=True, encoding="utf-8", errors="replace")
    report = read_json(prepared["report"]) if prepared["report"].is_file() else None
    return result.returncode, report


def main() -> int:
    parser = argparse.ArgumentParser(description="从封存源码胶囊执行唯一一次独立盲测")
    parser.add_argument("--project-root", type=Path, required=True)
    parser.add_argument("--baseline", type=Path, required=True)
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument("--gold", type=Path, required=True)
    parser.add_argument("--report", type=Path, required=True)
    parser.add_argument("--log", type=Path, required=True)
    args = parser.parse_args()
    prepared = prepare_run(args.project_root.resolve(), args.baseline.resolve(), args.manifest.resolve(),
                           args.gold.resolve(), args.report.resolve(), args.log.resolve())
    return_code, report = execute_run(prepared)
    if report is None:
        raise RuntimeError(f"blind evaluation produced no report; inspect {prepared['log']}")
    print(f"首次盲测状态: {report['status']}")
    print(f"报告: {prepared['report']}")
    return 0 if report["status"] in {"STRONG_PASS", "STAGE_PASS"} and return_code == 0 else 1


if __name__ == "__main__":
    raise SystemExit(main())
