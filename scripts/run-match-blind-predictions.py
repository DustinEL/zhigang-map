"""使用冻结 JAR 和封存清单生成一次性人岗匹配预测。"""

import argparse
import hashlib
import http.cookiejar
import json
import mimetypes
import os
import shutil
import socket
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
from collections import Counter
from datetime import datetime, timezone
from pathlib import Path


LABELS = ("需先补关键能力", "接近可申请", "可申请实习/校招")
STANDARD_ROLE_IDS = {2, 3, 4, 5, 7, 9, 12}
ARTIFACT_PATHS = {
    "jarSha256": "backend/target/zhigang-backend-1.0.0.jar",
    "capabilityCardsSha256": "backend/src/main/resources/matching/role-capability-cards-v2.json",
    "resumeEvidenceExtractorSha256": "backend/src/main/java/com/zhigang/platform/career/ResumeEvidenceExtractor.java",
    "matchDecisionEngineSha256": "backend/src/main/java/com/zhigang/platform/career/MatchDecisionEngine.java",
}


class PredictionError(ValueError):
    pass


def file_sha256(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def canonical_sha256(value):
    return hashlib.sha256(json.dumps(value, ensure_ascii=False, sort_keys=True,
                                     separators=(",", ":")).encode("utf-8")).hexdigest()


def _load_finalizer():
    import importlib.util
    path = Path(__file__).with_name("finalize-match-blind-intake.py")
    spec = importlib.util.spec_from_file_location("match_blind_finalizer", path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def validate_manifest(manifest, root, official=True):
    root = Path(root).resolve()
    if manifest.get("status") != "FROZEN_FOR_PREDICTION":
        raise PredictionError("manifest status 必须为 FROZEN_FOR_PREDICTION")
    if manifest.get("containsLabels") is not False:
        raise PredictionError("manifest 不得包含标签")
    if not str(manifest.get("datasetId", "")).strip():
        raise PredictionError("manifest missing datasetId")
    stored_hash = str(manifest.get("datasetSha256", ""))
    unhashed = dict(manifest)
    unhashed.pop("datasetSha256", None)
    if stored_hash != canonical_sha256(unhashed):
        raise PredictionError("datasetSha256 校验失败")
    resumes = manifest.get("resumes")
    roles = manifest.get("roles")
    cases = manifest.get("cases")
    if not all(isinstance(value, list) for value in (resumes, roles, cases)):
        raise PredictionError("manifest 必须包含 resumes、roles、cases 数组")
    if (len(resumes), len(roles), len(cases)) != (
            int(manifest.get("plannedResumeCount", -1)),
            int(manifest.get("plannedRoleCount", -1)),
            int(manifest.get("plannedCaseCount", -1))):
        raise PredictionError("manifest 计划计数与实际数组不一致")
    if official and (len(resumes) != 30 or len(roles) != 7 or len(cases) != 210):
        raise PredictionError("正式预测必须完整覆盖 30×7=210 对")

    artifacts = manifest.get("artifacts") or {}
    for name, relative in ARTIFACT_PATHS.items():
        path = (root / relative).resolve()
        if not path.is_file() or artifacts.get(name) != file_sha256(path):
            raise PredictionError(f"冻结运行制品哈希不一致: {name}")

    leakage = manifest.get("leakageAudit") or {}
    leakage_relative = Path(str(leakage.get("reportPath", "")))
    if leakage.get("status") != "PASS" or leakage_relative.is_absolute():
        raise PredictionError("leakage audit binding is invalid")
    leakage_path = (root / leakage_relative).resolve()
    if not leakage_path.is_relative_to(root) or not leakage_path.is_file() \
            or leakage.get("reportSha256") != file_sha256(leakage_path):
        raise PredictionError("leakage audit report hash mismatch")

    finalizer = _load_finalizer()
    resume_by_id = {}
    candidate_keys = set()
    content_hashes = set()
    normalized_hashes = set()
    paths = {}
    for resume in resumes:
        resume_id = str(resume.get("resumeId", "")).strip()
        candidate_key = str(resume.get("candidateKey", "")).strip()
        source_value = Path(str(resume.get("sourcePath", "")))
        if not resume_id or resume_id in resume_by_id or not candidate_key or candidate_key in candidate_keys:
            raise PredictionError("resumeId 与 candidateKey 必须非空且唯一")
        if source_value.is_absolute():
            raise PredictionError("sourcePath 必须是相对路径")
        source = (root / source_value).resolve()
        if not source.is_relative_to(root) or not source.is_file():
            raise PredictionError("sourcePath 逃逸根目录或文件不存在")
        if source.suffix.casefold() not in {".docx", ".pdf"} or source.stat().st_size > 10 * 1024 * 1024:
            raise PredictionError("正式预测只接受不超过 10MB 的 DOCX/PDF")
        content_hash = file_sha256(source)
        normalized_hash = finalizer.normalized_text_sha256(finalizer.extract_text(source))
        if int(resume.get("bytes", -1)) != source.stat().st_size \
                or resume.get("contentSha256") != content_hash \
                or resume.get("normalizedTextSha256") != normalized_hash:
            raise PredictionError(f"简历文件哈希或大小不一致: {resume_id}")
        if content_hash in content_hashes or normalized_hash in normalized_hashes:
            raise PredictionError("正式集合内部存在重复简历")
        if resume.get("split") != "blind" or str(resume.get("piiRedacted", "")).casefold() != "true":
            raise PredictionError("简历必须属于 blind 且完成 PII 脱敏")
        resume_by_id[resume_id] = resume
        candidate_keys.add(candidate_key)
        content_hashes.add(content_hash)
        normalized_hashes.add(normalized_hash)
        paths[resume_id] = source

    role_by_id = {}
    for role in roles:
        role_id = int(role.get("roleId", -1))
        if role_id in role_by_id:
            raise PredictionError("roleId 必须唯一")
        role_by_id[role_id] = role
    if official and set(role_by_id) != STANDARD_ROLE_IDS:
        raise PredictionError("正式预测岗位必须为 2,3,4,5,7,9,12")
    seen_cases = set()
    seen_pairs = set()
    for case in cases:
        forbidden_fields = {
            key for key in case
            if "label" in str(key).casefold()
            or str(key).casefold() in {"applicationstate", "predicted"}
        }
        if forbidden_fields:
            raise PredictionError(
                f"case contains forbidden label fields: {sorted(forbidden_fields)}")
        case_id = str(case.get("caseId", "")).strip()
        resume_id = str(case.get("resumeId", "")).strip()
        role_id = int(case.get("roleId", -1))
        if not case_id or case_id in seen_cases or (resume_id, role_id) in seen_pairs:
            raise PredictionError("caseId 和候选人岗位对必须唯一")
        role = role_by_id.get(role_id)
        if resume_id not in resume_by_id or role is None:
            raise PredictionError("case 引用了未知简历或岗位")
        for key in ("roleCode", "roleName", "roleVersion"):
            if case.get(key) != role.get(key):
                raise PredictionError("case 岗位身份与 roles 不一致")
        seen_cases.add(case_id)
        seen_pairs.add((resume_id, role_id))
    if seen_pairs != {(resume_id, role_id) for resume_id in resume_by_id for role_id in role_by_id}:
        raise PredictionError("cases 必须完整覆盖冻结的候选人岗位矩阵")
    context = manifest.get("evaluationContext") or {}
    if not isinstance(context.get("weeklyHours"), int) or not isinstance(context.get("graduationYear"), int):
        raise PredictionError("evaluationContext 缺少解析参数")
    return paths, context


def create_frozen_snapshot(manifest, root, destination, official=True):
    root = Path(root).resolve()
    destination = Path(destination).resolve()
    validate_manifest(manifest, root, official=official)
    destination.mkdir(parents=True, exist_ok=False)
    relative_paths = [str(item["sourcePath"]) for item in manifest["resumes"]]
    relative_paths.extend(ARTIFACT_PATHS.values())
    relative_paths.append(str(manifest["leakageAudit"]["reportPath"]))
    for relative in relative_paths:
        source = (root / relative).resolve()
        target = (destination / relative).resolve()
        if not target.is_relative_to(destination):
            raise PredictionError(f"snapshot path escapes destination: {relative}")
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(source, target)
    validate_manifest(manifest, destination, official=official)
    return destination


def run_predictions(manifest, root, client, official=True, diagnostic_details=False):
    paths, context = validate_manifest(manifest, root, official=official)
    cases_by_resume = {}
    for case in manifest["cases"]:
        cases_by_resume.setdefault(case["resumeId"], []).append(case)
    predictions = []
    policy_versions = set()
    for resume in manifest["resumes"]:
        resume_id = resume["resumeId"]
        profile = client.upload(paths[resume_id], context)
        backend_resume_id = profile.get("id")
        if not isinstance(backend_resume_id, int):
            raise PredictionError(f"上传未返回有效 resume id: {resume_id}")
        confirmed = client.confirm(backend_resume_id)
        if confirmed.get("id") != backend_resume_id or confirmed.get("status") != "CONFIRMED":
            raise PredictionError(f"简历确认响应不一致: {resume_id}")
        for case in cases_by_resume.get(resume_id, []):
            match = client.match(backend_resume_id, case, context)
            state = match.get("applicationState")
            if state not in LABELS or match.get("resumeId") != backend_resume_id \
                    or int(match.get("roleId", -1)) != int(case["roleId"]):
                raise PredictionError(f"匹配响应身份或类别非法: {case['caseId']}")
            policy = str(match.get("scoringPolicyVersion", "")).strip()
            if not policy:
                raise PredictionError(
                    f"match response missing scoringPolicyVersion: {case['caseId']}")
            policy_versions.add(policy)
            prediction = {
                "caseId": case["caseId"], "resumeId": resume_id,
                "roleId": case["roleId"], "roleCode": case["roleCode"],
                "roleName": case["roleName"], "roleVersion": case["roleVersion"],
                "applicationState": state, "scoringPolicyVersion": policy,
            }
            if diagnostic_details:
                prediction["diagnostic"] = {
                    "resumeAbilities": profile.get("abilities", []),
                    "resumeProjects": profile.get("projects", []),
                    "resumeFacts": profile.get("facts", []),
                    "readiness": match.get("readiness"),
                    "hardGates": match.get("hardGates", []),
                    "dimensions": match.get("dimensions", []),
                    "skillDetails": match.get("skillDetails", []),
                }
            predictions.append(prediction)
    if len(predictions) != len(manifest["cases"]):
        raise PredictionError("预测数量与 manifest 不一致")
    if len(policy_versions) != 1:
        raise PredictionError("prediction run must use one scoringPolicyVersion")
    counts = Counter(item["applicationState"] for item in predictions)
    return {
        "schemaVersion": "match-blind-predictions-v1.0",
        "generatedAt": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
        "datasetId": manifest["datasetId"], "datasetSha256": manifest["datasetSha256"],
        "artifactHashes": manifest["artifacts"],
        "resumeCount": len(manifest["resumes"]), "roleCount": len(manifest["roles"]),
        "caseCount": len(predictions),
        "stateCounts": [{"state": label, "count": counts[label]} for label in LABELS],
        "scoringPolicyVersions": sorted(policy_versions), "predictions": predictions,
    }


class ApiClient:
    def __init__(self, base_uri):
        cookies = http.cookiejar.CookieJar()
        self.opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(cookies))
        self.base_uri = base_uri.rstrip("/")

    def _json(self, method, path, body=None, content_type="application/json"):
        data = None if body is None else (json.dumps(body, ensure_ascii=False).encode("utf-8")
                                          if content_type == "application/json" else body)
        request = urllib.request.Request(self.base_uri + path, data=data, method=method,
                                         headers={"Content-Type": content_type} if data is not None else {})
        try:
            with self.opener.open(request, timeout=120) as response:
                envelope = json.loads(response.read().decode("utf-8"))
        except (urllib.error.URLError, UnicodeDecodeError, json.JSONDecodeError) as error:
            raise PredictionError(f"后端请求失败: {path}") from error
        if envelope.get("code") != "OK" or envelope.get("data") is None:
            raise PredictionError(f"后端返回无效结果: {path}")
        return envelope["data"]

    def upload(self, path, context):
        boundary = "----zhigang-" + uuid.uuid4().hex
        mime = ("application/pdf" if path.suffix.casefold() == ".pdf" else
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document")
        prefix = (f"--{boundary}\r\nContent-Disposition: form-data; name=\"file\"; "
                  f"filename=\"resume{path.suffix.casefold()}\"\r\nContent-Type: {mime}\r\n\r\n").encode("ascii")
        body = prefix + path.read_bytes() + f"\r\n--{boundary}--\r\n".encode("ascii")
        query = urllib.parse.urlencode({"weeklyHours": context["weeklyHours"],
                                        "graduationYear": context["graduationYear"]})
        return self._json("POST", f"/api/resumes/files?{query}", body,
                          f"multipart/form-data; boundary={boundary}")

    def confirm(self, backend_resume_id):
        return self._json("PUT", f"/api/resumes/{backend_resume_id}/facts", {"confirmed": True})

    def match(self, backend_resume_id, case, context):
        return self._json("POST", "/api/match-runs", {
            "resumeId": backend_resume_id, "jobRoleId": int(case["roleId"]),
            "preferredCity": context.get("preferredCity", ""),
            "workMode": context.get("workMode", ""),
        })


def wait_healthy(base_uri, process, timeout=90):
    deadline = time.time() + timeout
    while time.time() < deadline:
        if process.poll() is not None:
            raise PredictionError("冻结后端进程提前退出")
        try:
            with urllib.request.urlopen(base_uri + "/actuator/health", timeout=2) as response:
                if json.loads(response.read().decode("utf-8")).get("status") == "UP":
                    return
        except (urllib.error.URLError, json.JSONDecodeError, UnicodeDecodeError):
            time.sleep(0.5)
    raise PredictionError("冻结后端未在时限内启动")


def ensure_port_available(port):
    probe = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    try:
        probe.bind(("127.0.0.1", int(port)))
    except OSError as error:
        raise PredictionError(f"prediction server port is already in use: {port}") from error
    finally:
        probe.close()


def atomic_write_new(path, value):
    path = Path(path)
    if path.exists():
        raise PredictionError(f"拒绝覆盖正式预测: {path}")
    path.parent.mkdir(parents=True, exist_ok=True)
    fd, temp_name = tempfile.mkstemp(prefix=path.name + ".", suffix=".tmp", dir=path.parent)
    try:
        with os.fdopen(fd, "w", encoding="utf-8", newline="\n") as handle:
            json.dump(value, handle, ensure_ascii=False, indent=2)
            handle.write("\n")
        os.link(temp_name, path)
    finally:
        if os.path.exists(temp_name):
            os.unlink(temp_name)


def cleanup_snapshot(snapshot_temp, attempts=12):
    """Windows may release a just-stopped JVM's jar handle a little later."""
    if snapshot_temp is None:
        return
    for attempt in range(attempts):
        try:
            snapshot_temp.cleanup()
            return
        except PermissionError:
            if attempt + 1 == attempts:
                raise
            time.sleep(0.25)


def stop_process_tree(process):
    if process is None or process.poll() is not None:
        return
    if os.name == "nt":
        subprocess.run(["taskkill", "/PID", str(process.pid), "/T", "/F"],
                       stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, check=False)
    else:
        process.terminate()


def parse_args():
    root = Path(__file__).resolve().parents[1]
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument("--root", type=Path, default=root)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--java", default="java")
    parser.add_argument("--port", type=int, default=18190)
    parser.add_argument("--diagnostic-details", action="store_true")
    return parser.parse_args()


def main():
    args = parse_args()
    process = None
    snapshot_temp = None
    try:
        if args.output.exists():
            raise PredictionError(f"拒绝覆盖正式预测: {args.output}")
        manifest_bytes = args.manifest.read_bytes()
        manifest = json.loads(manifest_bytes.decode("utf-8"))
        validate_manifest(manifest, args.root, official=True)
        snapshot_temp = tempfile.TemporaryDirectory(prefix="zhigang-blind-snapshot-")
        runtime_root = create_frozen_snapshot(
            manifest, args.root, Path(snapshot_temp.name) / "frozen", official=True)
        ensure_port_available(args.port)
        base_uri = f"http://127.0.0.1:{args.port}"
        jar = runtime_root / ARTIFACT_PATHS["jarSha256"]
        creationflags = getattr(subprocess, "CREATE_NO_WINDOW", 0)
        process = subprocess.Popen([
            args.java, "-jar", str(jar), f"--server.port={args.port}",
            "--spring.profiles.active=demo", "--zhigang.security.dev-permit-all=true",
        ], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, creationflags=creationflags)
        wait_healthy(base_uri, process)
        report = run_predictions(
            manifest, runtime_root, ApiClient(base_uri), official=True,
            diagnostic_details=args.diagnostic_details)
        report["manifestFileSha256"] = hashlib.sha256(manifest_bytes).hexdigest()
        report["runtime"] = {"baseUri": base_uri, "selfLaunchedFrozenJar": True}
        atomic_write_new(args.output, report)
        print(f"已生成 {report['caseCount']} 条正式预测: {args.output.resolve()}")
        return 0
    except (OSError, json.JSONDecodeError, PredictionError) as error:
        print(f"错误: {error}", file=sys.stderr)
        return 1
    finally:
        if process is not None:
            stop_process_tree(process)
            try:
                process.wait(timeout=10)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait(timeout=10)
        if snapshot_temp is not None:
            cleanup_snapshot(snapshot_temp)


if __name__ == "__main__":
    raise SystemExit(main())
