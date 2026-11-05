import argparse
import hashlib
import json
from pathlib import Path


DATASET_ID = "match-blind-96-v1"
SELECTED_RESUMES = (
    ("08", "08_岳子烨_全栈算法AI", "08_岳子烨_全栈算法AI.docx", "ai-llm"),
    ("09", "09_ShreyaMishra_软件AI数据工程", "09_ShreyaMishra_软件AI数据工程.docx", "data-ml"),
    ("10", "10_玄锐暮_Java后端AI全栈", "10_玄锐暮_Java后端AI全栈.docx", "java-backend"),
    ("12", "12_ErikPallasigue_后端开发", "12_ErikPallasigue_后端开发.docx", "java-backend"),
    ("13", "13_BhartiKumari_数据机器学习", "13_BhartiKumari_数据机器学习.docx", "data-ml"),
    ("20", "20_GaganJain", "20_GaganJain_AI_LLM工程师.docx", "ai-llm"),
    ("21", "21_RubenMarcus", "21_RubenMarcus_AI全栈工程师.docx", "frontend-fullstack"),
    ("23", "23_DaliaAlkilani", "23_DaliaAlkilani_AI_NLP工程师.docx", "ai-llm"),
    ("24", "24_VladislavZheleznyak", "24_VladislavZheleznyak_C++.docx", "cpp-systems"),
    ("25", "25_SwangitiSharma", "25_SwangitiSharma_前端工程师.docx", "frontend-fullstack"),
    ("27", "27_HarshKumar", "27_HarshKumar_Java后端.docx", "java-backend"),
    ("30", "30_MithileshPabba", "30_MithileshPabba_计算机科学数据科学.docx", "data-ml"),
)
ROLES = (
    (2, "Java 开发工程师"),
    (4, "AI 应用后端工程师"),
    (3, "AI Agent 开发工程师"),
    (5, "LLMOps / 模型服务工程师"),
    (7, "大模型应用开发工程师"),
    (9, "AI 数据工程师"),
    (12, "AI 安全测试工程师"),
    (13, "大模型推理加速工程师"),
)


def sha256_file(path):
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def build_manifest(root):
    resumes = []
    for resume_id, resume_name, filename, stratum in SELECTED_RESUMES:
        source = root / "测试简历" / resume_name / filename
        if not source.is_file():
            raise FileNotFoundError(f"未找到盲测简历: {source}")
        relative_path = source.relative_to(root).as_posix()
        resumes.append(
            {
                "resumeId": resume_id,
                "resumeName": resume_name,
                "stratum": stratum,
                "docx": {
                    "path": relative_path,
                    "sha256": sha256_file(source),
                    "bytes": source.stat().st_size,
                },
            }
        )

    roles = [{"roleId": role_id, "roleName": role_name} for role_id, role_name in ROLES]
    cases = []
    case_number = 1
    for resume in resumes:
        for role in roles:
            cases.append(
                {
                    "caseId": f"MB-{case_number:03d}",
                    "resumeId": resume["resumeId"],
                    "roleId": role["roleId"],
                }
            )
            case_number += 1

    manifest = {
        "schemaVersion": 1,
        "datasetId": DATASET_ID,
        "resumes": resumes,
        "roles": roles,
        "cases": cases,
    }
    canonical = json.dumps(
        manifest,
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
    ).encode("utf-8")
    manifest["datasetSha256"] = hashlib.sha256(canonical).hexdigest()
    return manifest


def annotation_markdown(manifest):
    resume_names = {resume["resumeId"]: resume["resumeName"] for resume in manifest["resumes"]}
    role_names = {role["roleId"]: role["roleName"] for role in manifest["roles"]}
    lines = [
        "# 人岗匹配独立盲测人工标注表",
        "",
        f"数据集：`{manifest['datasetId']}`  ",
        f"清单哈希：`{manifest['datasetSha256']}`",
        "",
        "请仅依据简历原文和岗位要求填写“人工标签”。允许值：",
        "`可申请实习/校招`、`接近可申请`、`需先补关键能力`、`无法判断`。",
        "“判断依据”请简述关键证据；信息不足时填写“无法判断”并在备注说明。",
        "",
        "| Case ID | 简历编号 | 简历 | 岗位编号 | 岗位 | 人工标签 | 判断依据 | 备注 |",
        "|---|---:|---|---:|---|---|---|---|",
    ]
    for case in manifest["cases"]:
        lines.append(
            "| {case_id} | {resume_id} | {resume_name} | {role_id} | {role_name} |  |  |  |".format(
                case_id=case["caseId"],
                resume_id=case["resumeId"],
                resume_name=resume_names[case["resumeId"]],
                role_id=case["roleId"],
                role_name=role_names[case["roleId"]],
            )
        )
    return "\n".join(lines) + "\n"


def write_outputs(root, manifest_path, annotation_path):
    manifest = build_manifest(root)
    manifest_path.parent.mkdir(parents=True, exist_ok=True)
    annotation_path.parent.mkdir(parents=True, exist_ok=True)
    manifest_path.write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )
    annotation_path.write_text(annotation_markdown(manifest), encoding="utf-8")


def parse_args():
    project_root = Path(__file__).resolve().parents[1]
    parser = argparse.ArgumentParser(description="生成独立人岗匹配盲测清单和人工标注表")
    parser.add_argument("--root", type=Path, default=project_root)
    parser.add_argument(
        "--manifest",
        type=Path,
        default=project_root / "test-data" / "evaluation" / "match-blind-96-manifest.json",
    )
    parser.add_argument(
        "--annotation",
        type=Path,
        default=project_root / "test-data" / "evaluation" / "match-blind-96-annotation.md",
    )
    return parser.parse_args()


def main():
    args = parse_args()
    write_outputs(args.root.resolve(), args.manifest.resolve(), args.annotation.resolve())


if __name__ == "__main__":
    main()
