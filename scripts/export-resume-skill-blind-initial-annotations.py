from __future__ import annotations

import argparse
import json
from pathlib import Path

from openpyxl import load_workbook

from resume_skill_blind_freeze import export_initial_annotations


def main() -> int:
    parser = argparse.ArgumentParser(description="在人工全量复核前封存 Codex 独立初标")
    parser.add_argument("--data-dir", type=Path, required=True)
    args = parser.parse_args()
    data_dir = args.data_dir.resolve()
    manifest = json.loads((data_dir / "resume-skill-blind-30-manifest.json").read_text(encoding="utf-8"))
    workbook = load_workbook(data_dir / "resume-skill-blind-30-annotation.xlsx", data_only=False)
    exported = export_initial_annotations(workbook, manifest)
    output = data_dir / "resume-skill-blind-30-initial-annotations.json"
    try:
        with output.open("x", encoding="utf-8") as stream:
            json.dump(exported, stream, ensure_ascii=False, indent=2)
            stream.write("\n")
    except FileExistsError as exception:
        raise FileExistsError(f"refusing to overwrite initial annotations: {output}") from exception
    print(f"已封存 Codex 初标: {output}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
