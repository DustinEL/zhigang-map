# 授权数据源定期采集

该目录只把具有明确许可的来源采集到本地待审核区，不会直接修改网站快照或数据库。

支持的适配器：

- `MANUAL_BATCH`：本地 CSV、JSON、XLSX。
- `STRUCTURED_DATASET`：本地或公网 HTTPS 的 JSON、CSV。
- `OFFICIAL_ATS`：经确认允许公开访问的 HTTPS JSON、CSV、RSS/Atom。
- `PUBLIC_EMPLOYMENT`：政府、高校或公共就业来源的 HTTPS JSON、CSV、RSS/Atom。
- `ENTERPRISE_CAREERS`：企业招聘站或企业授权导出的 HTTPS JSON、CSV、RSS/Atom。

使用步骤：

1. 复制 `sources.example.json` 为被忽略的 `sources.local.json`。
2. 填写 `sourceId`、来源级别、适配器、时间范围、许可依据和入口，将 `enabled` 改为 `true`。
3. 运行：

```powershell
node "collector/collect-authorized-sources.mjs" "collector/sources.local.json" "collector/inbox"
```

输出固定为 `PENDING_LOCAL_REVIEW`。精确重复按 SHA-256 过滤；SimHash 近重复只建立 `PENDING_HUMAN_CONFIRMATION` 关系，不自动删除。完成人工核对、事实抽取、原文位置验证和质量门禁后，才可转为结构化导入文件。

采集器不会自动跟随重定向、访问 HTTP/私网地址、绕过登录或验证码、读取大于 10MB 的响应，也不会在采集成功后自动发布。

运行采集器测试：

```powershell
node --test "collector/tests/*.test.mjs"
```

## 中国 JD 比赛语料

比赛语料使用 `build-jd-corpus.mjs`，来源配置在 `jd-corpus-sources.json`。当前只接入无需登录的中国公开来源：国聘、美团招聘官网、腾讯招聘官网、百度招聘官网、国家大学生就业服务平台。

```powershell
node "collector/build-jd-corpus.mjs" --output "test-data/jd-corpus"
node "collector/validate-jd-corpus.mjs" "test-data/jd-corpus"
node "collector/export-chinese-review.mjs" "test-data/jd-corpus"
```

采集器只保留来源、企业和工作地点均为中国大陆的中文原文 JD，输出默认待人工审核；不会把海外 JD 翻译后混入正式语料。工作地点采用结构化国家/区域字段和中国大陆行政区白名单双重门禁，无法确认的地点一律拒绝。

腾讯适配器只接受官网接口明确标注国家为“中国”的职位，并再次按中国大陆城市过滤；详情同时固化岗位职责和任职要求。新增或替换正式语料前，先输出到独立验证目录并运行 `validate-jd-corpus.mjs`。

百度适配器只读取官网公开渲染的职位列表，并逐条访问 `/jobs/detail/GRADUATE/{postId}` 验证职位有效性；不调用登录接口，也不构造页面中不存在的岗位。
