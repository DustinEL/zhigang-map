from __future__ import annotations

import base64
import binascii
from typing import Annotated

import numpy as np
from fastapi import FastAPI, HTTPException
from pydantic import BaseModel, ConfigDict, Field, model_validator
from sklearn.cluster import HDBSCAN
from sklearn.preprocessing import normalize


MODEL_VERSION = "sklearn-hdbscan-1.5.1"
OCR_MODEL_VERSION = "PaddleOCR-2.9.1"
app = FastAPI(title="智岗图谱岗位聚类工作器", version=MODEL_VERSION)
_ocr_engine = None


def to_camel(value: str) -> str:
    head, *tail = value.split("_")
    return head + "".join(part.capitalize() for part in tail)


class ApiModel(BaseModel):
    model_config = ConfigDict(alias_generator=to_camel, populate_by_name=True)


class ClusterRequest(ApiModel):
    document_ids: Annotated[list[int], Field(min_length=1)]
    vectors: Annotated[list[list[float]], Field(min_length=1)]
    min_cluster_size: int = Field(default=5, ge=2)
    min_samples: int = Field(default=3, ge=1)

    @model_validator(mode="after")
    def validate_shape(self) -> "ClusterRequest":
        if len(self.document_ids) != len(self.vectors):
            raise ValueError("document_ids 与 vectors 数量不一致")
        dimensions = {len(vector) for vector in self.vectors}
        if len(dimensions) != 1 or not dimensions or next(iter(dimensions)) == 0:
            raise ValueError("所有向量必须具有相同且非零的维度")
        if len(set(self.document_ids)) != len(self.document_ids):
            raise ValueError("document_ids 不允许重复")
        return self


class ClusterAssignment(ApiModel):
    document_id: int
    label: int
    probability: float
    noise: bool


class ClusterResponse(ApiModel):
    algorithm: str
    vector_dimension: int
    assignments: list[ClusterAssignment]


class ResumeOcrRequest(ApiModel):
    file_name: str = Field(min_length=1, max_length=255)
    content_base64: str = Field(min_length=8, max_length=15_000_000)


class ResumeOcrResponse(ApiModel):
    text: str
    page_count: int
    model: str


@app.get("/health")
def health() -> dict[str, str]:
    return {"status": "UP", "model": MODEL_VERSION}


@app.post("/v1/clusters", response_model=ClusterResponse)
def cluster(request: ClusterRequest) -> ClusterResponse:
    if len(request.vectors) < request.min_cluster_size:
        raise HTTPException(status_code=422, detail="样本数少于 min_cluster_size")
    matrix = np.asarray(request.vectors, dtype=np.float64)
    if not np.isfinite(matrix).all():
        raise HTTPException(status_code=422, detail="向量包含非有限数值")
    normalized = normalize(matrix, norm="l2", axis=1)
    estimator = HDBSCAN(
        min_cluster_size=request.min_cluster_size,
        min_samples=request.min_samples,
        metric="euclidean",
        cluster_selection_method="eom",
        allow_single_cluster=False,
        n_jobs=1,
    )
    labels = estimator.fit_predict(normalized)
    probabilities = estimator.probabilities_
    assignments = [
        ClusterAssignment(
            document_id=document_id,
            label=int(label),
            probability=round(float(probability), 6),
            noise=int(label) == -1,
        )
        for document_id, label, probability in zip(
            request.document_ids, labels, probabilities, strict=True
        )
    ]
    return ClusterResponse(
        algorithm=MODEL_VERSION,
        vector_dimension=int(matrix.shape[1]),
        assignments=assignments,
    )


def get_ocr_engine():
    global _ocr_engine
    if _ocr_engine is None:
        try:
            # Paddle 2.6.2 会加载自带 zlib；先加载 pyclipper，避免二者的动态符号发生冲突。
            import pyclipper
            from paddleocr import PaddleOCR
        except ImportError as exception:
            raise RuntimeError("PaddleOCR 运行时未安装") from exception
        _ocr_engine = PaddleOCR(
            use_angle_cls=True,
            lang="ch",
            show_log=False,
            use_gpu=False,
            enable_mkldnn=False,
            use_tensorrt=False,
            ir_optim=False,
        )
    return _ocr_engine


def render_pdf_pages(content: bytes):
    try:
        import pypdfium2 as pdfium
    except ImportError as exception:
        raise RuntimeError("pypdfium2 运行时未安装") from exception
    document = pdfium.PdfDocument(content)
    if len(document) == 0 or len(document) > 20:
        raise ValueError("扫描 PDF 页数必须在 1–20 页之间")
    return [document[index].render(scale=2.0).to_pil() for index in range(len(document))]


def flatten_ocr_result(result) -> list[str]:
    lines: list[str] = []
    for page in result or []:
        if not isinstance(page, list):
            continue
        for item in page:
            if (isinstance(item, (list, tuple)) and len(item) >= 2
                    and isinstance(item[1], (list, tuple)) and item[1]
                    and isinstance(item[1][0], str)):
                value = item[1][0].strip()
                if value:
                    lines.append(value)
    return lines


@app.post("/v1/resume-ocr", response_model=ResumeOcrResponse)
def ocr_resume(request: ResumeOcrRequest) -> ResumeOcrResponse:
    if not request.file_name.lower().endswith(".pdf"):
        raise HTTPException(status_code=422, detail="OCR 仅处理扫描版 PDF")
    try:
        content = base64.b64decode(request.content_base64, validate=True)
    except (binascii.Error, ValueError) as exception:
        raise HTTPException(status_code=422, detail="PDF Base64 内容无效") from exception
    if len(content) < 5 or len(content) > 10 * 1024 * 1024 or not content.startswith(b"%PDF-"):
        raise HTTPException(status_code=422, detail="PDF 内容无效或超过 10MB")
    try:
        pages = render_pdf_pages(content)
        engine = get_ocr_engine()
        lines: list[str] = []
        for image in pages:
            lines.extend(flatten_ocr_result(engine.ocr(np.asarray(image), cls=True)))
    except ValueError as exception:
        raise HTTPException(status_code=422, detail=str(exception)) from exception
    except RuntimeError as exception:
        raise HTTPException(status_code=503, detail=str(exception)) from exception
    except Exception as exception:
        raise HTTPException(status_code=500, detail="PaddleOCR 解析失败") from exception
    text = "\n".join(lines).strip()
    if not text:
        raise HTTPException(status_code=422, detail="扫描 PDF 未识别到文字")
    return ResumeOcrResponse(text=text, page_count=len(pages), model=OCR_MODEL_VERSION)
