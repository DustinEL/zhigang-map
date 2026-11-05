# 数据说明

## 1. 仓库里有什么

- `frontend/public/data`：岗位能力图谱的公开冻结快照，用于静态演示。岗位信息来自公开招聘页面，仅用于演示，版权归原来源。
- `test-data/examples`：最小输入 / 输出示例，只用于冒烟回归，不能计算正式指标。
- `database/*.sql`：schema、治理脚本和 demo seed，不包含生产数据。

## 2. 仓库里没有什么

以下内容被 `.gitignore` 排除，或本来就没有复制进来：

- `.env.local`、API Key、数据库密码、Cookie；
- `database/backups` 数据库备份；
- `database/competition` 比赛私有数据；
- `test-data` 中的真实简历、金标、盲测预测；
- `.models` 模型权重；
- `deploy/static-releases` 发布包；
- 本机绝对路径、日志、临时文件。

## 3. 如何导入自己的数据

1. 复制 `.env.example` 为 `.env.local`，填写 MySQL 与模型配置。
2. 运行 `docker compose up -d --build` 启动完整环境。
3. 通过专业端“真实数据”入口导入 CSV / JSON，或使用 `collector/` 的授权采集脚本。
4. 导入前确认数据来源许可；不要导入无权使用的简历或招聘数据。

## 4. 隐私

- 简历解析前会脱敏姓名、电话、邮箱、地址。
- 匿名会话有清理机制。
- 不要提交真实简历、联系方式、数据库备份或截图中的个人信息。
- 如果要做公开演示，使用合成数据或人工脱敏数据。

## 5. 数据口径

页面统一标识 `REAL`、`REAL_PENDING`、`DEMO`、`FROZEN_CACHE`、`NO_DATA` 或 `ERROR`。静态快照固定为 `FROZEN_CACHE`，不会伪装成实时数据。
