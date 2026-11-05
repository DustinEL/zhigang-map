# P0-2：可信抽取与 RAG 主链

## 目标

将岗位文本抽取从“规则命中即返回”升级为可验证、可降级、可审计的主链：规则负责高置信字段，模型只补规则缺口，任何输出都必须回到原文证据；授权证据可进入 Qdrant，并使用 `bge-m3` 向量检索；证据数量不满足门禁时只返回“证据不足”。

## 主链

1. 输入文本一律视为不可信数据，先检测提示注入。
2. 标题、薪资、职级、技能、职责和行业场景优先走确定性规则。
3. 仅当规则存在缺口且未命中提示注入时，调用 `ModelGateway.generateStructured()`。
4. 模型输出必须满足固定 JSON Schema；未知字段、缺失字段和错误类型整体降级。
5. 技能、职责、行业场景逐条校验 `documentId`、`startOffset`、`endOffset` 与原文引用；偏移不一致的字段进入异常队列，不进入发布结果。
6. 本地目录和 RSS/Atom 连接器只接收 `AUTHORIZED`/`OPEN` 数据，保存后切分为证据片段并写入证据向量库。
7. 配置 Qdrant 与 OpenAI 兼容的 Embeddings 接口时使用 `bge-m3`；未配置时保留本地授权证据检索，规则抽取不受影响。
8. 回答证据问题前先检索证据，再执行独立企业数和来源数门禁；不满足时固定返回“证据不足”。

## 可观测指标

- 证据覆盖率：带有效原文锚点的结构化字段 / 全部结构化字段。
- 引用一致率：偏移与原文逐字一致的引用 / 全部待校验引用。
- 无证据拒答率：证据不足请求中被正确拒绝的比例。
- 错误接受率：证据不足请求中仍生成结论的比例。
- 提示注入拦截率：检测到注入后成功阻止模型调用的比例。

接口：`GET /api/job-descriptions/extractions/metrics`。

## 关键配置

| 环境变量 | 说明 | 默认值 |
|---|---|---|
| `QDRANT_ENDPOINT` | Qdrant HTTP 地址 | 空（本地证据回退） |
| `QDRANT_COLLECTION` | 授权证据集合 | `zhigang_authorized_evidence` |
| `EMBEDDING_API_ENDPOINT` | OpenAI 兼容 `/v1/embeddings` 地址 | 空 |
| `EMBEDDING_MODEL` | 向量模型 | `bge-m3` |
| `CONCLUSION_MIN_COMPANIES` | 结论最少独立企业数 | `2` |
| `CONCLUSION_MIN_SOURCES` | 结论最少独立来源数 | `1` |

Docker Compose 默认启动固定摘要的 `llama.cpp` CPU 服务，并在后端启动前下载、校验和准备 `bge-m3 Q4_K_M` 模型。运行时镜像、模型 URL 与模型 SHA-256 均可审计，模型与 Qdrant 数据保存在 Docker 持久卷中。宿主机直接运行后端时，可将 `EMBEDDING_API_ENDPOINT` 设置为 `http://127.0.0.1:11434/v1/embeddings`。

所有对外证据对象额外返回稳定的 `citation_id`。该标识由 `documentId/startOffset/endOffset` 确定性生成，并与 Qdrant point ID 保持一致，便于跨接口审计同一原文锚点。

零样本指标统一返回 `0`，避免把“尚无观测”误报为 `100%`；产生有效样本后再按实际分子和分母计算。

真实向量链验收可在服务启动后执行：

```powershell
$env:RUN_QDRANT_E2E="true"
$env:QDRANT_E2E_ENDPOINT="http://127.0.0.1:6333"
$env:EMBEDDING_E2E_ENDPOINT="http://127.0.0.1:11434/v1/embeddings"
$env:QDRANT_E2E_COLLECTION="zhigang_authorized_evidence_e2e_bge_m3"
./backend/mvnw.cmd -f ./backend/pom.xml -Dtest=QdrantEvidenceVectorStoreE2ETest test
```

该验收会使用隔离集合写入两家独立企业的 `AUTHORIZED` 证据，验证 `bge-m3` 生成 1024 维向量、Qdrant Cosine 写入与查询，以及 point ID 和 `citation_id` 的一致性。

## 验收标准

- 模型 JSON 不合法或不符合 Schema 时，结果降级且不污染规则字段。
- 模型证据偏移错误时，字段进入异常队列且错误接受率保持为 0。
- 提示注入文本不会触发模型调用。
- 每个已发布技能、职责和行业场景都有原文锚点。
- 关闭模型、Qdrant 或向量服务后，规则通道继续正常运行。
- 授权证据不足时，解释接口不调用生成结论并返回“证据不足”。
