import unittest

from fastapi import HTTPException
from pydantic import ValidationError

from app import ClusterRequest, ResumeOcrRequest, cluster, flatten_ocr_result, ocr_resume


class ClusterTests(unittest.TestCase):
    def test_clusters_two_groups_and_keeps_noise(self) -> None:
        vectors = [
            [1.0, 0.01], [0.99, 0.02], [0.98, -0.01], [1.01, 0.0], [0.97, 0.03],
            [0.01, 1.0], [0.02, 0.99], [-0.01, 0.98], [0.0, 1.01], [0.03, 0.97],
            [-1.0, -1.0],
        ]
        result = cluster(ClusterRequest(
            document_ids=list(range(1, 12)), vectors=vectors, min_cluster_size=5, min_samples=2
        ))
        labels = {item.label for item in result.assignments if not item.noise}
        self.assertEqual(2, len(labels))
        self.assertTrue(any(item.noise for item in result.assignments))


    def test_rejects_inconsistent_dimensions(self) -> None:
        with self.assertRaises(ValidationError):
            ClusterRequest(document_ids=[1, 2], vectors=[[1.0, 0.0], [1.0]], min_cluster_size=2)

    def test_rejects_non_finite_vectors(self) -> None:
        request = ClusterRequest(document_ids=[1, 2], vectors=[[1.0, 0.0], [float("nan"), 1.0]], min_cluster_size=2)

        with self.assertRaises(HTTPException) as error:
            cluster(request)

        self.assertEqual(422, error.exception.status_code)

    def test_rejects_too_few_samples(self) -> None:
        request = ClusterRequest(document_ids=[1, 2], vectors=[[1.0, 0.0], [0.0, 1.0]], min_cluster_size=3)

        with self.assertRaises(HTTPException) as error:
            cluster(request)

        self.assertEqual(422, error.exception.status_code)

    def test_uses_java_camel_case_contract(self) -> None:
        request = ClusterRequest.model_validate({
            "documentIds": list(range(1, 6)),
            "vectors": [[1.0, index / 100] for index in range(5)],
            "minClusterSize": 5,
            "minSamples": 2,
        })

        result = cluster(request).model_dump(by_alias=True)

        self.assertEqual(2, request.min_samples)
        self.assertEqual(2, result["vectorDimension"])
        self.assertIn("documentId", result["assignments"][0])

    def test_flattens_paddle_ocr_lines(self) -> None:
        result = [[
            [[[0, 0], [1, 0], [1, 1], [0, 1]], ["学历：本科", 0.99]],
            [[[0, 2], [1, 2], [1, 3], [0, 3]], ["项目：使用 Java 完成接口测试", 0.97]],
        ]]

        self.assertEqual(["学历：本科", "项目：使用 Java 完成接口测试"], flatten_ocr_result(result))

    def test_rejects_invalid_ocr_payload_before_loading_paddle(self) -> None:
        with self.assertRaises(HTTPException) as error:
            ocr_resume(ResumeOcrRequest(fileName="resume.pdf", contentBase64="bm90LWEtcGRm"))

        self.assertEqual(422, error.exception.status_code)


if __name__ == "__main__":
    unittest.main()
