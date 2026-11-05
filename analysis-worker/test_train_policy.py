import unittest

from train_policy import FEATURES, calibrate


def sample(value: float, label: int, publishable: int, variant: int) -> dict[str, float | int]:
    result: dict[str, float | int] = {feature: value for feature in FEATURES}
    result.update({
        "label": label,
        "publishable": publishable,
        "existingVariant": variant,
        "nearestRoleSimilarity": 0.92 if variant else 0.35,
    })
    return result


class PolicyCalibrationTests(unittest.TestCase):
    def test_trains_on_development_and_calibrates_on_validation(self) -> None:
        development = [sample(0.1, 0, 0, 1), sample(0.2, 0, 0, 1),
                       sample(0.8, 1, 1, 0), sample(0.9, 1, 1, 0)]
        validation = [sample(0.15, 0, 0, 1), sample(0.3, 0, 0, 1),
                      sample(0.7, 1, 0, 0), sample(0.95, 1, 1, 0)]
        base = {
            "candidateGate": {}, "publishGate": {}, "clustering": {},
            "semanticProfileVersion": "v1", "timeWindowDays": 90,
        }

        policy = calibrate(development, validation, base, 0.8)

        self.assertEqual(set(FEATURES), set(policy["weights"]))
        self.assertGreaterEqual(policy["publishThreshold"], policy["candidateThreshold"])
        self.assertEqual(0.92, policy["existingRoleSimilarityThreshold"])

    def test_rejects_validation_without_independent_labels(self) -> None:
        development = [sample(0.1, 0, 0, 1), sample(0.9, 1, 1, 0)]
        validation = [sample(0.2, 0, 0, 1), sample(0.8, 1, 1, 0)]
        del validation[0]["publishable"]

        with self.assertRaises(ValueError):
            calibrate(development, validation, {}, 0.8)


if __name__ == "__main__":
    unittest.main()
