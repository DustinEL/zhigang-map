# 数据库与 Redis 配置

## 一、先区分软件和服务

Navicat 是 MySQL 的图形客户端，不是 MySQL 数据库服务本身。

Another Redis Desktop Manager 是 Redis 的图形客户端，不是 Redis 服务本身。

连接项目前，请先确认本机已经安装并启动：

1. MySQL 8.x 服务。
2. Redis 服务（可选）。

当前项目默认使用 demo 配置，不连接 MySQL 和 Redis，也可以启动和演示。

## 二、使用 Navicat 创建 MySQL 数据库

1. 在 Navicat 中连接本机 MySQL。
2. 新建查询。
3. 完整执行 database/01_schema.sql。
4. 再执行 database/02_seed_demo.sql。
5. 最后执行 database/03_real_data_pipeline.sql。
6. 刷新数据库列表，应看到 zhigang_map 数据库。

如果执行失败，优先检查 MySQL 是否为 8.x。脚本使用 JSON、全文索引和 utf8mb4_0900_ai_ci 排序规则。

## 三、配置 Spring Boot 连接 MySQL

配置文件位置：

backend/src/main/resources/application-mysql.yml

需要修改或通过环境变量提供：

MYSQL_URL、MYSQL_USERNAME、MYSQL_PASSWORD。

配置文件中已经用 TODO 标出。不要把真实密码提交到 Git。

启动时切换配置：

```powershell
$env:SPRING_PROFILES_ACTIVE="mysql"
$env:MYSQL_USERNAME="root"
$env:MYSQL_PASSWORD="你的MySQL密码"
mvn spring-boot:run
```

如果只想先看效果，保持默认配置并执行：

```powershell
mvn spring-boot:run
```

默认 demo 配置会使用后端演示种子，不访问数据库。

## 四、Redis 是否必须

不必须。

第七版 P0 仍不依赖 Redis。当前代码在 Redis 关闭时可以完整运行，避免首次配置时同时排查 MySQL 和 Redis 两套问题。

Redis 后续适合用于：

1. 相同 JD/简历输入哈希的模型结果缓存。
2. 短期处理任务状态。
3. 接口限流计数。
4. 图谱市场卡短时缓存。

## 五、以后启用 Redis

先在 Another Redis Desktop Manager 中确认能连接：

```text
主机：127.0.0.1
端口：6379
密码：按你的 Redis 配置填写，没有则留空
数据库：0
```

再设置环境变量：

```powershell
$env:ZHIGANG_REDIS_ENABLED="true"
$env:REDIS_HOST="127.0.0.1"
$env:REDIS_PORT="6379"
$env:REDIS_PASSWORD=""
```

项目已经保留 spring-boot-starter-data-redis 依赖和连接配置。真正增加缓存时，建议单独建立 CacheService 接口，业务模块不要直接操作 RedisTemplate。

## 六、常见连接错误

Communications link failure：

MySQL 服务未启动、端口不对，或 URL 中数据库名错误。

Access denied for user：

用户名或密码错误，或当前用户没有 zhigang_map 权限。

Unknown database zhigang_map：

还没有执行 01_schema.sql。

Table dynamic_record doesn't exist：

还没有执行 03_real_data_pipeline.sql。

Redis Connection refused：

Redis 服务没有启动。桌面管理软件能打开不代表 Redis 服务已启动。

## 七、授权目录和在线订阅

授权目录保持留空时不会扫描任何本地文件。需要启用时设置：

```powershell
$env:ZHIGANG_IMPORT_DIRECTORY="<你的已授权岗位文件目录>"
```

在线连接器只支持官方或已获授权的 HTTPS RSS/Atom。没有明确许可时保持留空，不要填写 BOSS 直聘、猎聘等页面地址：

```powershell
$env:ZHIGANG_RSS_URL="https://官方域名/jobs.xml"
$env:ZHIGANG_RSS_SOURCE_NAME="官方招聘订阅"
$env:ZHIGANG_RSS_SOURCE_TYPE="RECRUITMENT_JD"
$env:ZHIGANG_RSS_LICENSE_STATUS="OPEN"
$env:ZHIGANG_RSS_LICENSE_NOTE="官方公开 RSS，允许自动读取"
```

配置后重启项目，可在“专业工作台 -> 数据与质量 -> 批量导入”查看连接状态并手动同步。第七版结构化 JD 请在“专业工作台 -> 真实数据闭环”导入和审核。
