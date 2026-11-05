from __future__ import annotations

import argparse
import hashlib
import json
import re
from collections import Counter, defaultdict
from datetime import datetime, timezone
from pathlib import Path

import numpy as np
from scipy.optimize import Bounds, LinearConstraint, milp
from scipy.sparse import coo_matrix


TARGETS = {
    "AI_AGENT_ENGINEER": 30,
    "JAVA_ENGINEER": 25,
    "RAG_APP_ENGINEER": 25,
    "AI_APP_BACKEND_ENGINEER": 25,
    "LLMOPS_ENGINEER": 20,
    "LLM_EVALUATION_ENGINEER": 20,
    "LLM_APP_ENGINEER": 25,
    "AI_PLATFORM_ENGINEER": 20,
    "AI_DATA_ENGINEER": 20,
    "KNOWLEDGE_ENGINEER": 10,
    "VECTOR_RETRIEVAL_ENGINEER": 10,
    "AI_SECURITY_TEST_ENGINEER": 10,
}
MINIMUMS = {
    "AI_AGENT_ENGINEER": 27,
    "JAVA_ENGINEER": 22,
    "RAG_APP_ENGINEER": 22,
    "AI_APP_BACKEND_ENGINEER": 22,
    "LLMOPS_ENGINEER": 18,
    "LLM_EVALUATION_ENGINEER": 18,
    "LLM_APP_ENGINEER": 22,
    "AI_PLATFORM_ENGINEER": 18,
    "AI_DATA_ENGINEER": 18,
    "KNOWLEDGE_ENGINEER": 8,
    "VECTOR_RETRIEVAL_ENGINEER": 8,
    "AI_SECURITY_TEST_ENGINEER": 8,
}
MAXIMUMS = {
    "AI_AGENT_ENGINEER": 33,
    "JAVA_ENGINEER": 28,
    "RAG_APP_ENGINEER": 28,
    "AI_APP_BACKEND_ENGINEER": 28,
    "LLMOPS_ENGINEER": 22,
    "LLM_EVALUATION_ENGINEER": 22,
    "LLM_APP_ENGINEER": 28,
    "AI_PLATFORM_ENGINEER": 22,
    "AI_DATA_ENGINEER": 22,
    "KNOWLEDGE_ENGINEER": 12,
    "VECTOR_RETRIEVAL_ENGINEER": 12,
    "AI_SECURITY_TEST_ENGINEER": 12,
}
LEVEL_MINIMUMS = {"初级或实习": 24, "中级": 84, "高级或管理岗": 84, "未注明": 0}
LEVEL_TARGETS = {"初级或实习": 36, "中级": 96, "高级或管理岗": 96, "未注明": 0}
LEVEL_MAXIMUMS = {"初级或实习": 48, "中级": 108, "高级或管理岗": 108, "未注明": 48}
MINIMUM_CHINESE_CHARACTERS = 120


def read_jsonl(path: Path) -> list[dict]:
    return [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines() if line.strip()]


def chinese_character_count(text: object) -> int:
    return len(re.findall(r"[\u4e00-\u9fff]", str(text or "")))


class UnionFind:
    def __init__(self):
        self.parent = {}

    def add(self, value):
        self.parent.setdefault(value, value)

    def find(self, value):
        self.add(value)
        if self.parent[value] != value:
            self.parent[value] = self.find(self.parent[value])
        return self.parent[value]

    def union(self, left, right):
        left_root, right_root = self.find(left), self.find(right)
        if left_root != right_root:
            self.parent[right_root] = left_root


def derive_level(record: dict) -> str:
    normalized_level = str(record.get("jobLevel") or "").strip()
    if normalized_level in {"初级或实习", "中级", "高级或管理岗"}:
        return normalized_level
    title = str(record.get("jobTitle") or "").casefold()
    experience = str(record.get("jobExperience") or "")
    text = f"{title} {experience}"
    if re.search(r"实习|校招|应届|intern|junior|entry", text):
        return "初级或实习"
    years = [int(value) for value in re.findall(r"\d+", experience)]
    raw_text = str(record.get("rawText") or "")
    years.extend(int(value) for value in re.findall(
        r"(\d+)\s*年(?:以上|及以上|或以上)?[^。；，,\n]{0,12}经验",
        raw_text,
    ))
    years.extend(int(value) for value in re.findall(
        r"经验[^0-9。；，,\n]{0,6}(\d+)\s*年",
        raw_text,
    ))
    if re.search(r"高级|资深|专家|架构师|负责人|总监|首席|principal|staff|lead|senior", text) or (years and max(years) >= 5):
        return "高级或管理岗"
    if "中级" in text or (years and max(years) >= 2):
        return "中级"
    return "未注明"


def normalized_hash(text: str) -> str:
    return hashlib.sha256(text.encode("utf-8")).hexdigest()


def solve_assignments(pool: list[dict], size: int) -> list[tuple[dict, str]]:
    assignments = [
        (record_index, role)
        for record_index, record in enumerate(pool)
        for role in record["_roles"]
    ]
    companies = sorted({str(record.get("company") or "") for record in pool})
    sources = sorted({str(record.get("sourcePlatform") or "") for record in pool})
    cities = sorted({str(record.get("city") or "") for record in pool})
    variable_count = len(assignments)
    company_variables = {key: variable_count + index for index, key in enumerate(companies)}
    variable_count += len(companies)
    source_variables = {key: variable_count + index for index, key in enumerate(sources)}
    variable_count += len(sources)
    city_variables = {key: variable_count + index for index, key in enumerate(cities)}
    variable_count += len(cities)
    company_excess = {key: variable_count + index for index, key in enumerate(companies)}
    variable_count += len(companies)
    role_deviation = {}
    for role in TARGETS:
        role_deviation[role] = (variable_count, variable_count + 1)
        variable_count += 2
    level_deviation = {}
    for level in LEVEL_TARGETS:
        level_deviation[level] = (variable_count, variable_count + 1)
        variable_count += 2

    objective = np.zeros(variable_count)
    integrality = np.zeros(variable_count, dtype=int)
    lower_bounds = np.zeros(variable_count)
    upper_bounds = np.full(variable_count, np.inf)
    upper_bounds[:len(assignments)] = 1
    integrality[:len(assignments)] = 1
    for index, (record_index, role) in enumerate(assignments):
        record = pool[record_index]
        objective[index] = (0 if record.get("primaryTargetRoleCode") == role else 0.01)
        objective[index] += 0.1 if record["_derivedLevel"] == "未注明" else 0
        objective[index] += index * 1e-9
    for variables in (company_variables, source_variables, city_variables):
        for index in variables.values():
            integrality[index] = 1
            upper_bounds[index] = 1
    for index in company_variables.values():
        objective[index] = -1
    for index in source_variables.values():
        objective[index] = -0.1
    for index in city_variables.values():
        objective[index] = -0.01
    for index in company_excess.values():
        objective[index] = 1_000
        upper_bounds[index] = 4
    for positive, negative in role_deviation.values():
        objective[positive] = objective[negative] = 100
    for positive, negative in level_deviation.values():
        objective[positive] = objective[negative] = 10

    constraints = []
    constraint_lower = []
    constraint_upper = []

    def add_constraint(coefficients, lower=-np.inf, upper=np.inf):
        constraints.append(coefficients)
        constraint_lower.append(lower)
        constraint_upper.append(upper)

    all_assignment_coefficients = {index: 1 for index in range(len(assignments))}
    add_constraint(all_assignment_coefficients, size, size)
    by_record = defaultdict(dict)
    by_role = defaultdict(dict)
    by_level = defaultdict(dict)
    by_company = defaultdict(dict)
    by_source = defaultdict(dict)
    by_city = defaultdict(dict)
    for index, (record_index, role) in enumerate(assignments):
        record = pool[record_index]
        by_record[record_index][index] = 1
        by_role[role][index] = 1
        by_level[record["_derivedLevel"]][index] = 1
        by_company[str(record.get("company") or "")][index] = 1
        by_source[str(record.get("sourcePlatform") or "")][index] = 1
        by_city[str(record.get("city") or "")][index] = 1
    for coefficients in by_record.values():
        add_constraint(coefficients, upper=1)
    for role in TARGETS:
        add_constraint(by_role[role], MINIMUMS[role], MAXIMUMS[role])
        positive, negative = role_deviation[role]
        add_constraint({**by_role[role], positive: -1, negative: 1}, TARGETS[role], TARGETS[role])
    for level in LEVEL_TARGETS:
        add_constraint(by_level[level], LEVEL_MINIMUMS[level], LEVEL_MAXIMUMS[level])
        positive, negative = level_deviation[level]
        add_constraint({**by_level[level], positive: -1, negative: 1}, LEVEL_TARGETS[level], LEVEL_TARGETS[level])
    max_per_source = int(size * 0.45)
    for source, indicator in source_variables.items():
        add_constraint({**by_source[source], indicator: -max_per_source}, upper=0)
        add_constraint({**{index: -value for index, value in by_source[source].items()}, indicator: 1}, upper=0)
    add_constraint({index: 1 for index in source_variables.values()}, lower=5)
    for company, indicator in company_variables.items():
        add_constraint({**by_company[company], indicator: -10}, upper=0)
        add_constraint({**{index: -value for index, value in by_company[company].items()}, indicator: 1}, upper=0)
        add_constraint({**by_company[company], company_excess[company]: -1}, upper=6)
    add_constraint({index: 1 for index in company_variables.values()}, lower=60)
    for city, indicator in city_variables.items():
        add_constraint({**by_city[city], indicator: -size}, upper=0)
        add_constraint({**{index: -value for index, value in by_city[city].items()}, indicator: 1}, upper=0)
    add_constraint({index: 1 for index in city_variables.values()}, lower=12)

    row_indices = []
    column_indices = []
    values = []
    for row_index, coefficients in enumerate(constraints):
        for column_index, value in coefficients.items():
            row_indices.append(row_index)
            column_indices.append(column_index)
            values.append(value)
    matrix = coo_matrix((values, (row_indices, column_indices)), shape=(len(constraints), variable_count)).tocsr()
    result = milp(
        objective,
        integrality=integrality,
        bounds=Bounds(lower_bounds, upper_bounds),
        constraints=LinearConstraint(matrix, constraint_lower, constraint_upper),
        options={"time_limit": 120},
    )
    if not result.success:
        raise ValueError({"solverStatus": result.status, "solverMessage": result.message})
    selected = [
        (pool[record_index], role)
        for index, (record_index, role) in enumerate(assignments)
        if result.x[index] > 0.5
    ]
    return sorted(selected, key=lambda item: str(item[0].get("recordId") or ""))


def select(candidates: list[dict], findings: list[dict], size: int = 240) -> tuple[list[dict], dict]:
    exposed = {str(item["candidateRecordId"]) for item in findings if item.get("scope") == "candidate-exposed"}
    union = UnionFind()
    for item in findings:
        if item.get("scope") == "candidate-internal":
            union.union(str(item["candidateRecordId"]), str(item["exposedRecordId"]))
    internal_components = defaultdict(list)
    for record in candidates:
        if chinese_character_count(record.get("rawText")) < MINIMUM_CHINESE_CHARACTERS:
            continue
        record_id = str(record["recordId"])
        if record_id not in exposed:
            internal_components[union.find(record_id)].append(record)

    def representative(records):
        return max(records, key=lambda record: (
            derive_level(record) != "未注明",
            len(record.get("matchedRoleCodes") or []),
            str(record.get("sourcePlatform") or ""),
            str(record.get("recordId") or ""),
        ))

    pool = [representative(records) for records in internal_components.values()]
    for record in pool:
        record["_derivedLevel"] = derive_level(record)
        record["_roles"] = [role for role in record.get("matchedRoleCodes", []) if role in TARGETS]
    pool = [record for record in pool if record["_roles"]]

    selected = solve_assignments(pool, size)
    assigned_roles = Counter(role for _, role in selected)
    source_counts = Counter(str(record.get("sourcePlatform") or "") for record, _ in selected)
    company_counts = Counter(str(record.get("company") or "") for record, _ in selected)
    level_counts = Counter(record["_derivedLevel"] for record, _ in selected)
    city_counts = Counter(str(record.get("city") or "") for record, _ in selected)

    if len(selected) != size or any(
        not MINIMUMS[role] <= assigned_roles[role] <= MAXIMUMS[role]
        for role in TARGETS
    ):
        raise ValueError({
            "selected": len(selected),
            "assignedRoles": dict(assigned_roles),
            "minimums": MINIMUMS,
            "targets": TARGETS,
            "maximums": MAXIMUMS,
            "maximumCompanyCount": max(company_counts.values(), default=0),
            "sourceCounts": dict(source_counts),
        })
    if len(source_counts) < 5 or len(company_counts) < 60 or len(city_counts) < 12:
        raise ValueError({"sources": len(source_counts), "companies": len(company_counts), "cities": len(city_counts)})
    if any(
        not LEVEL_MINIMUMS[level] <= level_counts[level] <= LEVEL_MAXIMUMS[level]
        for level in LEVEL_MINIMUMS
    ):
        raise ValueError({"levels": dict(level_counts)})

    output = []
    for index, (record, role) in enumerate(selected, start=1):
        cleaned = {key: value for key, value in record.items() if not key.startswith("_")}
        job_title = str(record.get("jobTitle") or "").strip()
        body = str(record.get("rawText") or "")
        raw_text = body if job_title and job_title in body else f"{job_title}\n{body}"
        cleaned["candidateRecordId"] = record.get("recordId")
        cleaned["recordId"] = f"HJD-20260816-{index:04d}"
        cleaned["companyName"] = record.get("companyName") or record.get("company")
        cleaned.setdefault("industry", None)
        cleaned["primaryTargetRoleCode"] = role
        cleaned["secondaryTargetRoleCodes"] = [
            candidate_role
            for candidate_role in record.get("matchedRoleCodes", [])
            if candidate_role in TARGETS and candidate_role != role
        ]
        cleaned["level"] = record["_derivedLevel"]
        cleaned["rawText"] = raw_text
        cleaned["contentHash"] = normalized_hash(raw_text)
        cleaned["contentSha256"] = cleaned["contentHash"]
        cleaned["collector"] = record.get("collector") or "PUBLIC_WEB_COLLECTOR"
        output.append(cleaned)
    below_minimum = [
        record["recordId"]
        for record in output
        if chinese_character_count(record.get("rawText")) < MINIMUM_CHINESE_CHARACTERS
    ]
    selected_at = datetime.now(timezone.utc).isoformat()
    return output, {
        "schemaVersion": "1.0.0",
        "recordCount": len(output),
        "byPrimaryTargetRole": dict(Counter(record["primaryTargetRoleCode"] for record in output)),
        "bySourcePlatform": dict(Counter(record.get("sourcePlatform") for record in output)),
        "byCompany": dict(Counter(record.get("company") for record in output)),
        "byCity": dict(Counter(record.get("city") for record in output)),
        "byLevel": dict(Counter(record.get("level") for record in output)),
        "minimumChineseCharacters": MINIMUM_CHINESE_CHARACTERS,
        "belowMinimumChineseCharacterCount": len(below_minimum),
        "belowMinimumChineseCharacterRecordIds": below_minimum,
        "records": [
            {
                "recordId": record["recordId"],
                "candidateRecordId": record.get("candidateRecordId"),
                "sourceName": record.get("sourceName"),
                "sourceType": record.get("sourceType"),
                "sourceUrl": record.get("sourceUrl"),
                "companyName": record.get("companyName"),
                "jobTitle": record.get("jobTitle"),
                "city": record.get("city"),
                "level": record.get("level"),
                "primaryTargetRoleCode": record.get("primaryTargetRoleCode"),
                "secondaryTargetRoleCodes": record.get("secondaryTargetRoleCodes", []),
                "contentSha256": record.get("contentSha256"),
            }
            for record in output
        ],
        "selectedAt": selected_at,
        "frozenAt": selected_at,
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--candidate", type=Path, required=True)
    parser.add_argument("--audit", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--manifest", type=Path, required=True)
    args = parser.parse_args()
    candidates = read_jsonl(args.candidate)
    findings = json.loads(args.audit.read_text(encoding="utf-8"))["findings"]
    selected, summary = select(candidates, findings)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text("".join(json.dumps(record, ensure_ascii=False) + "\n" for record in selected), encoding="utf-8")
    args.manifest.write_text(json.dumps(summary, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(summary, ensure_ascii=False))


if __name__ == "__main__":
    main()
