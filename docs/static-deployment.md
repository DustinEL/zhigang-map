# 无数据库静态部署

## 运行边界

生产服务器只运行 Nginx，提供 Vue 静态资源和 `data/current.json`。不安装或运行 MySQL、Redis、Java、Docker 和常驻爬虫。当前 282 条中国计算机相关 JD 以 `FROZEN_CACHE` 标识，覆盖 84 家企业、5 个平台和 3 类来源，代表本轮冻结快照。

学生端的文字档案、手动档案、岗位匹配和成长计划在浏览器内存中计算，刷新页面即清除。静态版不上传或解析 PDF/DOCX。专业端只查看冻结资料、证据、候选技能和发布机制，不支持在线登录或持久化审核。

## 本地生成快照

项目默认从 `data/JD结构化试验/jd_structured.json` 生成：

```powershell
cd "<项目根目录>/frontend"
npm run snapshot:build
npm run snapshot:validate
npm run build:static
```

生成文件：

- `public/data/current.json`：网站当前快照，不缓存或每次校验。
- `public/data/metadata.json`：版本和数据口径。
- `public/data/snapshots/<版本>.json`：历史冻结文件，可长期缓存。
- `dist-static/`：可直接交给 Nginx 的完整静态站点。

生成器先写临时文件，再原子替换 `current.json`。校验失败时，构建命令会停止。来源文件的 SHA-256 进入快照版本，用于判断数据是否变化。

如果使用其他结构化文件：

```powershell
node "scripts/build-frozen-snapshot.mjs" "<结构化数据文件路径>" "public/data"
```

输入必须保持 `schemaVersion=1.0.0`、来源许可、原文、内容哈希、技能事实、原文偏移和质量状态。

## 定期采集

复制 `collector/sources.example.json` 为 `collector/sources.local.json`，只配置具有书面授权、开放接口条款或明确允许自动读取的数据源。运行：

```powershell
cd "<项目根目录>"
node "collector/collect-authorized-sources.mjs" "collector/sources.local.json" "collector/inbox"
```

采集结果固定进入 `PENDING_LOCAL_REVIEW`，不会自动替换线上快照。后续通过统一一致性门禁核对来源和许可，完成结构化、去重、证据偏移检查及候选技能检查；全部通过后再运行快照生成器，不需要另行安排双人标注或仲裁。

Windows 任务计划程序可每周执行一次采集命令。采集失败时不修改 `current.json`，网站继续使用上一次冻结快照。

## 制作部署包

```powershell
cd "<项目根目录>"
./deploy/build-static-package.ps1
```

输出位于 `deploy/static-releases/`。发布包只含静态网页、JSON、安装脚本、Nginx 示例和校验和，不包含 JAR、SQL、数据库密码或网页专业账号。

## 服务器切换

切换前备份旧网站和当前 Nginx 配置，核对 `nginx-zhigangmap-static-ssl.conf` 的证书路径。上传并解压后执行：

```bash
sudo bash "/上传目录/install-static.sh" "/上传目录"
```

然后将 `deploy/nginx-zhigangmap-static-ssl.conf` 的内容安装到服务器实际生效的站点配置，执行 `nginx -t` 后再重载。该配置明确对 `/api/` 和 `/actuator/` 返回 404。

服务器最终只需监听 80/443，不开放 3306 或 8080。

## 更新与回退

每次更新都创建 `/opt/zhigangmap-static/releases/<时间>/`，`current` 软链接指向当前版本。回退时把软链接指回上一目录并重载 Nginx。

验收：

1. 首页显示“冻结缓存”。
2. `/data/current.json` 返回 `dataMode=FROZEN_CACHE`。
3. `/api/runtime-status` 返回 404。
4. 专业端显示只读提示，网页没有导入、审核、发布按钮。
5. 学生文字/手动诊断可用，刷新后个人档案消失。
6. 页面不把当前 282 条同批次样本伪装成跨时间窗口的历史趋势。
