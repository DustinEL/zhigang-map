# P0-6 完成说明

完成日期：2026-08-05

## 完成范围

1. 文本型 PDF/DOCX 使用 Apache Tika 解析；PDF 文本不足时转交 analysis-worker 的 PaddleOCR 路由，拒绝伪造 PDF、超过 20 页或无法解析的文件。
2. 简历进入事实抽取前先脱敏姓名、电话、邮箱和地址；页面只展示脱敏字段摘要，不回显原始身份信息。
3. 教育、专业、项目、技能和证书统一输出事实类型、规范值、原文偏移、引用片段、证据等级、来源类型、验证状态和置信度。
4. 手工修正简历后重新构建事实集合；未修改事实保留原锚点，新事实明确标记为 `USER_CORRECTION` 且不伪造原文偏移。
5. 技能匹配按精确词、别名、语义相似度分层执行；向量服务不可用时只保留确定性结果，不进行猜测式匹配。
6. 最终分数由冻结策略文件计算，大模型不直接给分；报告显示硬门槛、必备技能、加分技能、职责、场景、证据等级、策略版本、验证集哈希和评分置信度。
7. 能力证据采用 `PENDING_VERIFICATION`、`VERIFIED`、`REJECTED` 三态。链接可访问、文本相关、代码或文档存在、测试结果含可核验指标后仍先进入待人工复核状态。
8. 只有专业角色可以调用人工复核接口；只有 `VERIFIED` 证据能够升级能力并触发再次诊断。
9. 空链接、无法访问链接、无关文字、只有占位说明或没有指标的测试结果不能提高准备度。

## 评分策略与验证数据

- 策略文件：`backend/src/main/resources/scoring/p0-6-resume-match-v1.json`
- 冻结夹具：`test-data/evaluation/resume-match-validation.jsonl`
- SHA-256：`ccc95b1be504f57b598256d6db3f14ea27e4582ec4c56b5f04cb8e225399db76`
- 当前夹具共 6 条，只用于策略回归和冒烟校准；报告明确标记 `scope=smoke-fixture`，不将其表述为正式大样本评测。
- 夹具阈值 0.60 下 F1 为 1.000，Brier Score 四舍五入为 0.099。正式比赛指标仍应由独立冻结的大样本测试集生成。

## 数据库脚本

- 新环境初始化：`database/09_p0_6_resume_match_verification.sql`
- 后端迁移定义：`backend/src/main/resources/db/migration/V7__p0_6_resume_match_verification.sql`

脚本将旧验证状态统一到 `PENDING_VERIFICATION`，并新增以下审计表：

- `resume_parse_run`
- `resume_fact_anchor`
- `match_scoring_calibration`
- `match_score_audit`
- `verification_evidence_audit`
- `verification_review_record`

匿名简历主流程仍保持 7 天内存会话与立即删除能力，避免为 P0-6 过度引入完整个人履历仓库。上述表用于正式持久化环境的解析、评分和审核审计，不改变匿名会话隔离边界。

## 验收结果

- 后端全量测试：通过。
- P0-6 精确测试：简历文件、隐私脱敏、事实锚点、分层匹配、冻结评分、链接检查、证据验证全部通过。
- 权限集成测试：匿名请求被拒绝，`PROFESSIONAL` 角色可到达人工复核业务接口。
- 前端单元测试：4/4 通过。
- 前端生产构建：通过。
- 静态演示构建：通过；冻结快照为 317 份文档、10 个岗位。
- analysis-worker：9/9 通过；覆盖 OCR 请求校验、页数限制和结果扁平化。
- 真实 OCR Docker 镜像：构建通过。为保证可复现性固定 Cython 3.0.12、protobuf 4.25.8，并修复 PaddleOCR 2.9.1 忽略 `ir_optim` 参数的问题；通用 CPU 模式关闭 MKLDNN、TensorRT 和 IR 融合。
- 真实 OCR 推理：模型权重下载完成；1 页扫描式 PDF 经已部署的 `/v1/resume-ocr` 返回 `PaddleOCR-2.9.1`、页数 1，并将测试图像识别为 `JAVATEST123`。服务镜像固化后强制重建，第二次推理结果一致。
- 数据库隔离验收：一次性 MySQL 8.4 容器完整执行 `01–09`，并再次执行 `09`；6 张新表、两个默认状态、唯一评分策略和样本数 6 均验证通过，隔离容器已自动移除。
- 业务库迁移：在明确确认后应用于 `127.0.0.1:3306/zhigang_map`。迁移前备份为 `database/backups/zhigang_map-before-p0-6-20260805-122838.sql`，大小 10,997,056 字节，SHA-256 为 `5885A289650C3D9CC72464F564BB0CEAF4A11DF1B13CC181253AA47F80ED5739`。
- 业务库迁移后：6 张新表存在，`ability_evidence.verification_status` 与 `verification_submission.status` 默认值均为 `PENDING_VERIFICATION`，旧状态数量为 0；核心表行数保持 `8/317/13/0/0/2`，迁移前后未变化。

## 原则应用

- KISS：沿用现有匿名简历会话和 JD 标准化能力，只增加必要的 OCR、脱敏、分层匹配和审核边界。
- YAGNI：不新增用户历史简历中心，不让大模型决定分数，不为未确认需求建立第二套职业档案仓库。
- DRY：Tika/OCR 共用同一解析入口，技能标准化复用 JD 词典，验证状态和再次诊断共用一套证据规则。
- SOLID：隐私、OCR、匹配、评分、URL 检查和证据审核分别由单一服务负责；外部 URL 检查和向量能力通过抽象接口隔离。

## 最终交付

- 源码目录：`.`
- 最终压缩包：`./zhigang-p0-6-20260805-final.tar.gz`
- 压缩包排除 `.env.local`、`.models`、`node_modules`、`backend/target`、`database/backups`、`database/mysql-data`、`output`、`logs` 和 Git 元数据，不包含账号密码、业务库备份或本地模型缓存。
