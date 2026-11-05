import importlib.util
import unittest
from pathlib import Path


MODULE_PATH = Path(__file__).with_name("ai-preannotate-jd-workbook.py")


class AnnotationGroundingTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        spec = importlib.util.spec_from_file_location("ai_preannotate", MODULE_PATH)
        cls.module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(cls.module)

    def test_removes_list_number_without_rewriting_text(self):
        self.assertEqual("负责平台建设", self.module.clean_responsibility("1. 负责平台建设；"))

    def test_accepts_responsibility_copied_from_source(self):
        source = "岗位职责：\n1.负责平台建设与维护；"
        self.assertTrue(self.module.is_grounded("负责平台建设与维护", source))

    def test_rejects_hallucinated_responsibility(self):
        self.assertFalse(self.module.is_grounded("负责量子计算平台", "负责数据平台建设"))

    def test_extracts_only_numbered_duty_section(self):
        source = "公司介绍内容。\n岗位职责：\n1.负责平台建设；\n2.维护系统稳定。\n任职要求：\n1.熟悉Python。"
        self.assertEqual(
            ["负责平台建设", "维护系统稳定"],
            self.module.extract_responsibilities_from_source(source),
        )


if __name__ == "__main__":
    unittest.main()
