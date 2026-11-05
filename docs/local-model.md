# 免费本地模型使用说明

## 你现在使用的两个免费模型

- `bge-m3`：负责从岗位和证据中查找相关内容，用于向量检索和 RAG。
- `Qwen3-4B`：负责阅读系统提供的证据并生成岗位定义、结构化抽取和解释。

两个模型都在你的电脑上运行，没有 Token 数量限制。讯飞星火仍然保留，但只有你手动切换后才会调用。

## 第一次启动

1. 启动 Docker Desktop，等待左下角显示 Docker 已正常运行。
2. 打开项目目录 `./`。
3. 确认 `.env.local` 中包含下面一行：

   ```text
   AI_PROVIDER=local
   ```

4. 在项目目录运行：

   ```powershell
   docker compose up -d --build
   ```

5. 第一次启动会从 ModelScope 下载约 2.33GiB 的 Qwen 模型。模型保存在：

   ```text
   ./.models/generation/Qwen3-4B-Q4_K_M.gguf
   ```

6. 查看下载进度：

   ```powershell
   docker compose logs -f local-llm-model
   ```

   下载完成并校验通过后，按 `Ctrl+C` 只会退出日志查看，不会停止服务。

7. 查看服务状态：

   ```powershell
   docker compose ps
   ```

   `local-llm`、`backend`、`bge-m3`、`mysql` 应显示正常或健康。`local-llm-model` 显示已成功退出属于正常情况，因为它只负责准备模型。

8. 打开网站：`http://127.0.0.1:5173`。

## 确认当前不消耗星火 Token

运行：

```powershell
docker compose exec backend sh -lc 'printf "%s\n" "${AI_PROVIDER:-local}"'
```

显示 `local` 表示使用免费本地 Qwen。即使本地模型临时不可用，系统也只进入规则或证据不足状态，不会自动切换星火。

## 手动切换星火

只有确实需要备用模型时才执行下面步骤：

1. 打开 `.env.local`。
2. 把 `AI_PROVIDER=local` 改成：

   ```text
   AI_PROVIDER=spark
   ```

3. 确认 `SPARK_API_ENDPOINT`、`SPARK_API_KEY` 和 `SPARK_MODEL` 已正确填写。
4. 重新创建后端：

   ```powershell
   docker compose up -d --force-recreate backend
   ```

此后生成请求会消耗星火额度。系统不会同时调用本地模型和星火。

## 切回免费本地模型

1. 把 `.env.local` 改回：

   ```text
   AI_PROVIDER=local
   ```

2. 运行：

   ```powershell
   docker compose up -d --force-recreate backend
   ```

## 完全关闭生成模型

如果只想使用规则和已有证据，把 `.env.local` 设置为：

```text
AI_PROVIDER=rules
```

然后重新创建后端。该模式不会调用本地 Qwen，也不会调用星火。

## 常见问题

### 下载中断

重新运行：

```powershell
docker compose up -d local-llm
```

系统会重新下载临时文件，校验通过后才会使用，不会把不完整文件当成模型。

### 本地模型一直不健康

查看日志：

```powershell
docker compose logs --tail 200 local-llm
```

先确认 D 盘模型文件大小约为 2.33GiB，再确认 Docker Desktop 至少分配约 6GB 内存。不要删除 MySQL 数据卷或其他模型。

### 验证模型文件完整性

```powershell
Get-FileHash "./.models/generation/Qwen3-4B-Q4_K_M.gguf" -Algorithm SHA256
```

正确值应为：

```text
7485FE6F11AF29433BC51CAB58009521F205840F5B4AE3A32FA7F92E8534FDF5
```

### 网站能打开，但实时生成失败

后端会保持运行并安全降级。依次检查：

```powershell
docker compose ps
docker compose logs --tail 100 local-llm
docker compose logs --tail 100 backend
```

不要为了获得结果而降低证据数量、独立来源或字段引用门槛。

## 比赛录屏建议

1. 提前启动全部服务，让模型完成加载。
2. 先确认 `AI_PROVIDER` 的有效值为 `local`。
3. 先做一次结构化生成预热，再正式录制。
4. 展示 `bge-m3` 负责 RAG、本地 Qwen 负责生成、后端负责证据门禁的分工。
5. 展示字段级证据引用和不满足门禁时拒绝写入图谱，重点说明幻觉防控。
6. 公网静态站继续展示冻结可信结果；实时生成在本地动态系统中演示。
