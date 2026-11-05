from __future__ import annotations

import argparse
import hashlib
import subprocess
from pathlib import Path

from resume_skill_blind_baseline import create_baseline, sha256_file


PROJECT_ROOT = Path(__file__).resolve().parents[1]


def command_output(command: list[str], cwd: Path) -> str:
    result = subprocess.run(command, cwd=cwd, text=True, encoding="utf-8", errors="replace",
                            stdout=subprocess.PIPE, stderr=subprocess.STDOUT, check=True)
    return result.stdout.strip()


def discover_sources() -> list[Path]:
    backend = PROJECT_ROOT / "backend"
    files = [backend / "pom.xml"]
    files.extend(path for path in (backend / "src" / "main").rglob("*") if path.is_file())
    files.extend(path for path in (backend / ".mvn").rglob("*") if path.is_file())
    for name in ("mvnw", "mvnw.cmd"):
        path = backend / name
        if path.is_file():
            files.append(path)
    evaluation_dir = backend / "src" / "test" / "java" / "com" / "zhigang" / "platform" / "analysis"
    for name in ("ResumeSkillBlindThirtyEvaluationTest.java", "ResumeSkillBlindEvaluationSupport.java"):
        evaluation = evaluation_dir / name
        if not evaluation.is_file():
            raise ValueError(f"blind evaluation source is missing: {evaluation}")
        files.append(evaluation)
    return files


def main() -> int:
    parser = argparse.ArgumentParser(description="在盲测初标前创建不可覆盖的后端源码基线")
    parser.add_argument("--data-dir", type=Path, required=True)
    parser.add_argument("--old-gold-dir", type=Path, required=True)
    parser.add_argument("--output-dir", type=Path, required=True)
    args = parser.parse_args()

    backend = PROJECT_ROOT / "backend"
    output_dir = args.output_dir.resolve()
    output_dir.mkdir(parents=True, exist_ok=True)
    git_status = command_output(["git", "status", "--porcelain=v1"], PROJECT_ROOT)
    dependency_tree = command_output([str(backend / "mvnw.cmd"), "dependency:tree", "-DoutputType=text"], backend)
    dependency_path = output_dir / "resume-skill-blind-30-dependency-tree.txt"
    try:
        with dependency_path.open("x", encoding="utf-8") as stream:
            stream.write(dependency_tree + "\n")
    except FileExistsError as exception:
        raise FileExistsError(f"refusing to overwrite dependency tree: {dependency_path}") from exception
    protocol_path = args.data_dir.resolve() / "resume-skill-blind-30-protocol.md"
    metadata = {
        "gitHead": command_output(["git", "rev-parse", "HEAD"], PROJECT_ROOT),
        "gitStatusSha256": hashlib.sha256(git_status.encode("utf-8")).hexdigest(),
        "javaVersion": command_output(["java", "-version"], backend),
        "mavenVersion": command_output([str(backend / "mvnw.cmd"), "-version"], backend),
        "dependencyTreeSha256": sha256_file(dependency_path),
        "protocolSha256": sha256_file(protocol_path),
    }
    baseline = create_baseline(
        PROJECT_ROOT, args.data_dir.resolve(), args.old_gold_dir.resolve(), output_dir,
        discover_sources(), metadata,
    )
    print(f"源码树 SHA-256: {baseline['sourceTreeSha256']}")
    print(f"源码胶囊 SHA-256: {baseline['sourceCapsule']['sha256']}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
