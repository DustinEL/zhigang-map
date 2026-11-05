import importlib.util
import unittest
from pathlib import Path


SCRIPT = Path(__file__).with_name("evaluate-jd-pilot.py")


def load_module():
    spec = importlib.util.spec_from_file_location("evaluate_jd_pilot", SCRIPT)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class ParseResponsibilitiesTest(unittest.TestCase):
    def test_splits_inline_numbered_responsibilities(self):
        module = load_module()

        actual = module.parse_responsibilities("1.需求分析2、系统开发3）上线维护", "")

        self.assertEqual(["需求分析", "系统开发", "上线维护"], actual)

    def test_splits_bullets_and_semicolon_separated_responsibilities(self):
        module = load_module()

        actual = module.parse_responsibilities("● 模型训练；\n● 推理优化；", "")

        self.assertEqual(["模型训练", "推理优化"], actual)

    def test_no_responsibility_annotation_overrides_free_text(self):
        module = load_module()

        actual = module.parse_responsibilities("平台开发", "没有提到职责")

        self.assertEqual([], actual)

    def test_blank_without_explanation_is_unscored(self):
        module = load_module()

        actual = module.parse_responsibilities(None, "")

        self.assertIsNone(actual)


class SealVerificationTest(unittest.TestCase):
    def test_rejects_prediction_file_with_wrong_hash(self):
        module = load_module()

        with self.assertRaisesRegex(ValueError, "predictionsSha256"):
            module.verify_seal_values(b"inputs", b"predictions", {
                "sampleCount": 30,
                "inputsSha256": module.sha256_bytes(b"inputs"),
                "predictionsSha256": "0" * 64,
            }, 30)


if __name__ == "__main__":
    unittest.main()
