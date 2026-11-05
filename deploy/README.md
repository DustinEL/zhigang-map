# zhigangmap.xyz 生产部署

本版本部署目标是 512MB 公网服务器加远程 MySQL。公网服务器只运行 Nginx 和受限内存的 Spring Boot，不安装 Redis 或 MySQL。

## 上线前

1. 在远程 MySQL 创建 `zhigang_map` 与最小权限应用账号，只放行公网服务器 IP。
2. 完成旧网站、Nginx、旧 JAR 和远程 MySQL 备份。
3. 将真实密码写入 `/etc/zhigangmap/zhigang.env`，不要写入发布包。
4. 首次使用数据库时，按顺序执行发布包内 `database/01_schema.sql`、`03_real_data_pipeline.sql`、`04_review_triage.sql`；不执行 `02_seed_demo.sql`。

## 原子切换

上传完整发布目录到服务器后，先执行：

```bash
sudo env BACKUP_CONFIRMED=true bash /path/to/install-production.sh /path/to/release-directory
```

脚本会先验证备份确认、发布包哈希、生产环境变量、远程 MySQL 地址和服务器内存，再切换 `/opt/zhigangmap/current`。后端健康或运行模式不正确时会恢复上一版软链接。

然后明确指定当前 Nginx 站点配置路径并执行：

```bash
sudo env NGINX_SITE_CONFIG=/实际的/zhigangmap站点.conf bash /path/to/activate-nginx.sh /path/to/release-directory
```

该脚本会备份原 Nginx 站点配置，配置校验失败时自动恢复。必须保留当前证书路径；若服务器证书路径不同，先修改发布包的 `config/nginx-zhigangmap-ssl.conf` 再执行。

## 验收与回滚

1. 检查 `https://zhigangmap.xyz/`、`/professional/login`、`/api/runtime-status`。
2. 确认专业登录、学生诊断和真实 JD 页面均无 500。
3. 观察 30 分钟内存，Java 进程不得被 OOM 杀死。
4. 回滚应用时，将 `/opt/zhigangmap/current` 指回上一发布目录并重启 `zhigang-backend`；Nginx 使用备份文件恢复。
