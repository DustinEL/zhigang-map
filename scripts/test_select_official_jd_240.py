import importlib.util
import hashlib
import unittest
from pathlib import Path


SCRIPT = Path(__file__).with_name("select-official-jd-240.py")
SPEC = importlib.util.spec_from_file_location("select_official_jd_240", SCRIPT)
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)
VALID_RAW_TEXT = "\u5c97\u4f4d\u804c\u8d23\u4e0e\u4efb\u804c\u8981\u6c42" * 20


class OfficialJdSelectionTest(unittest.TestCase):
    def test_derive_level_uses_the_normalized_job_level(self):
        self.assertEqual("中级", MODULE.derive_level({
            "jobTitle": "平台工程师",
            "jobExperience": "",
            "jobLevel": "中级",
        }))

    def test_derive_level_uses_explicit_experience_evidence_in_body(self):
        self.assertEqual("中级", MODULE.derive_level({
            "jobTitle": "平台工程师",
            "jobExperience": "",
            "jobLevel": "未注明",
            "rawText": "任职要求：具备3年以上相关工作经验，熟悉分布式系统。",
        }))

    def test_accepts_role_counts_within_documented_ranges(self):
        role_counts = {
            "AI_AGENT_ENGINEER": 33,
            "JAVA_ENGINEER": 28,
            "RAG_APP_ENGINEER": 28,
            "AI_APP_BACKEND_ENGINEER": 22,
            "LLMOPS_ENGINEER": 18,
            "LLM_EVALUATION_ENGINEER": 18,
            "LLM_APP_ENGINEER": 28,
            "AI_PLATFORM_ENGINEER": 19,
            "AI_DATA_ENGINEER": 22,
            "KNOWLEDGE_ENGINEER": 8,
            "VECTOR_RETRIEVAL_ENGINEER": 8,
            "AI_SECURITY_TEST_ENGINEER": 8,
        }
        candidates = []
        role_codes = [role for role, count in role_counts.items() for _ in range(count)]
        for index, role in enumerate(role_codes):
            if index < 24:
                title, experience = "intern", ""
            elif index < 132:
                title, experience = "engineer", "3 years"
            else:
                title, experience = "senior engineer", "5 years"
            candidates.append({
                "recordId": f"ranged-{index:03d}",
                "jobTitle": title,
                "jobExperience": experience,
                "matchedRoleCodes": (
                    ["AI_AGENT_ENGINEER", role]
                    if role == "AI_APP_BACKEND_ENGINEER"
                    else [role]
                ),
                "sourcePlatform": f"source-{index % 5}",
                "company": f"company-{index % 60:02d}",
                "city": f"city-{index % 12}",
                "rawText": VALID_RAW_TEXT + f" {index}",
            })
        for index, role in enumerate(role_codes[:20]):
            candidates.append({
                "recordId": f"zz-dominant-{index:03d}",
                "jobTitle": "engineer",
                "jobExperience": "3 years",
                "matchedRoleCodes": [role],
                "sourcePlatform": f"source-{index % 5}",
                "company": "dominant-company",
                "city": f"dominant-city-{index}",
                "rawText": VALID_RAW_TEXT + f" surplus {index}",
            })
        candidates.append({
            "recordId": "short-candidate",
            "jobTitle": "engineer",
            "jobExperience": "3 years",
            "matchedRoleCodes": ["AI_AGENT_ENGINEER"],
            "sourcePlatform": "source-short",
            "company": "unique-short-company",
            "city": "unique-short-city",
            "rawText": "\u77ed\u6587\u672c",
        })
        for index, role in enumerate(role_codes[:60]):
            candidates.append({
                "recordId": f"zz-unknown-{index:03d}",
                "jobTitle": "engineer",
                "jobExperience": "",
                "jobLevel": "未注明",
                "matchedRoleCodes": [role],
                "sourcePlatform": f"source-{index % 5}",
                "company": f"unknown-company-{index:02d}",
                "city": f"unknown-city-{index}",
                "rawText": VALID_RAW_TEXT + f" unknown {index}",
            })

        selected, summary = MODULE.select(candidates, [], size=240)

        self.assertEqual(240, len(selected))
        self.assertEqual(role_counts, summary["byPrimaryTargetRole"])
        self.assertLessEqual(max(summary["byCompany"].values()), 6)
        self.assertTrue(all(
            sum("\u4e00" <= char <= "\u9fff" for char in record["rawText"]) >= 120
            for record in selected
        ))
        self.assertEqual(0, summary.get("belowMinimumChineseCharacterCount"))
        self.assertEqual([], summary.get("belowMinimumChineseCharacterRecordIds"))
        self.assertEqual(selected[0]["company"], selected[0].get("companyName"))
        self.assertTrue(selected[0].get("candidateRecordId", "").startswith("ranged-"))
        self.assertEqual("PUBLIC_WEB_COLLECTOR", selected[0].get("collector"))
        self.assertIn("industry", selected[0])
        self.assertIsInstance(selected[0].get("secondaryTargetRoleCodes"), list)
        self.assertTrue(selected[0]["rawText"].startswith(selected[0]["jobTitle"] + "\n"))
        self.assertEqual(
            hashlib.sha256(selected[0]["rawText"].encode("utf-8")).hexdigest(),
            selected[0]["contentSha256"],
        )
        self.assertEqual(240, len(summary.get("records", [])))
        self.assertEqual(selected[0]["recordId"], summary["records"][0]["recordId"])
        self.assertEqual(selected[0]["candidateRecordId"], summary["records"][0]["candidateRecordId"])
        self.assertEqual(selected[0]["contentSha256"], summary["records"][0]["contentSha256"])
        self.assertNotIn("rawText", summary["records"][0])
        self.assertLessEqual(summary["byLevel"].get("\u672a\u6ce8\u660e", 0), 48)
        self.assertGreaterEqual(summary["byLevel"].get("\u4e2d\u7ea7", 0), 84)

    def test_rejects_a_selection_that_requires_a_short_body(self):
        candidates = []
        role_codes = [role for role, count in MODULE.TARGETS.items() for _ in range(count)]
        for index, role in enumerate(role_codes):
            if index < 24:
                title, experience = "intern", ""
            elif index < 132:
                title, experience = "engineer", "3 years"
            else:
                title, experience = "senior engineer", "5 years"
            candidates.append({
                "recordId": f"minimum-{index:03d}",
                "jobTitle": title,
                "jobExperience": experience,
                "matchedRoleCodes": [role],
                "sourcePlatform": f"source-{index % 5}",
                "company": f"company-{index % 60:02d}",
                "city": f"city-{index % 12}",
                "rawText": "\u77ed\u6587\u672c" if index == 0 else VALID_RAW_TEXT + f" {index}",
            })

        with self.assertRaises(ValueError):
            MODULE.select(candidates, [], size=240)

    def test_treats_six_per_company_as_preference_and_ten_as_hard_limit(self):
        candidates = []
        role_codes = [
            role
            for role, count in MODULE.TARGETS.items()
            for _ in range(count)
        ]
        for index, role in enumerate(role_codes):
            if index < 7:
                company = "company-00"
            else:
                company = f"company-{1 + (index - 7) % 59:02d}"
            if index < 24:
                title, experience = "intern", ""
            elif index < 132:
                title, experience = "engineer", "3 years"
            else:
                title, experience = "senior engineer", "5 years"
            candidates.append({
                "recordId": f"candidate-{index:03d}",
                "jobTitle": title,
                "jobExperience": experience,
                "matchedRoleCodes": [role],
                "sourcePlatform": f"source-{index % 5}",
                "company": company,
                "city": f"city-{index % 12}",
                "rawText": VALID_RAW_TEXT + f" {index}",
            })

        selected, summary = MODULE.select(candidates, [], size=240)

        self.assertEqual(240, len(selected))
        self.assertEqual(60, len(summary["byCompany"]))
        self.assertEqual(7, max(summary["byCompany"].values()))
        self.assertLessEqual(max(summary["byCompany"].values()), 10)


if __name__ == "__main__":
    unittest.main()
