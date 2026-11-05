import json
import os
import re

import openpyxl


book = os.environ["JD_REVIEW_BOOK"]
output = os.environ["JD_REVIEW_OUTPUT"]
ws = openpyxl.load_workbook(book, data_only=True, read_only=True).worksheets[1]

skills = {
    35: ["人工智能", "机器学习", "深度学习", "大语言模型", "RAG", "智能体", "自然语言处理", "时间序列预测", "知识图谱", "推荐算法", "异常检测", "分类", "回归", "聚类", "文本分析", "语义检索", "向量检索", "Prompt工程", "Python", "Pandas", "NumPy", "Scikit-learn", "PyTorch", "TensorFlow", "LangChain", "向量数据库", "特征工程"],
    36: ["数据仓库", "数据平台", "数据治理", "数据建模", "数据可视化", "元数据管理", "数据Agent", "AI自动化运维", "Hadoop", "Hive", "Doris", "Spark", "Flink", "Kafka", "SQL"],
    37: ["大数据", "数据采集", "数据清洗", "数据存储", "数据模型", "数据质量监控", "SQL"],
    38: ["云原生数据库", "存算分离", "向量数据", "AI", "列存", "HTAP", "数据库自调优", "Apache Iceberg", "湖仓一体", "Serverless", "混沌工程", "分布式系统", "AI4DB", "DB4AI", "模型推理", "C++", "Rust", "Go", "Linux", "io_uring", "RDMA", "Neon", "Supabase", "AWS Aurora", "PostgreSQL", "MySQL", "HNSW", "DiskANN", "IVFFlat", "Delta Lake", "Vector DB", "RAG"],
    39: ["大模型", "数据管线", "对象存储分层", "冷热分层", "缓存策略", "数据压缩", "列式格式", "Python", "C++", "Java", "数据版本控制", "数据血缘", "可观测性", "DVC", "MLflow", "Delta Lake", "Data Catalog", "Spark", "Ray", "Flink", "Dask"],
    40: ["数据分析", "指标体系", "AB实验", "AI Agent", "LLM", "MCP", "SQL", "Skill", "数据仓库", "知识库", "Function Calling", "RAG", "统计分析"],
    41: ["人工智能", "大模型", "智能体", "Agent", "MaaS", "模型即服务", "算力调度", "强化学习", "分布式计算"],
    42: ["AI平台", "大模型", "分布式调度", "模型管理", "在线推理", "MLOps", "LLMOps", "GPU", "CPU", "异构计算", "边缘计算", "云原生", "DeepSpeed", "Horovod", "Ray", "TensorFlow Serving", "Triton", "Kubernetes", "Docker", "Kubeflow", "MLflow", "Airflow", "Argo", "Prometheus", "Python", "Go", "Java"],
    43: ["Multi-Agent", "Agent", "LLM", "运筹优化", "决策系统", "推荐系统", "Agent记忆系统", "工具调用"],
    44: ["AI HUB", "大模型", "模型训练", "模型微调", "数据流水线", "调度引擎", "分布式架构", "任务调度", "性能调优", "高并发"],
    45: ["网络与信息安全", "路由安全", "算力网络安全", "IPv6安全", "DNS安全", "网络安全攻防", "渗透测试", "AI安全", "安全大模型", "数据安全", "移动通信网络安全", "6G安全", "Java", "Python", "Linux脚本"],
    46: ["人工智能", "数据安全", "模型安全", "算法合规", "内容安全", "渗透测试", "对抗攻击", "对抗训练", "模型蒸馏", "联邦学习", "多方安全计算", "差分隐私", "隐私计算", "数据脱敏", "加密", "同态加密", "模型鲁棒性", "后门检测", "可解释性", "PyTorch", "TensorFlow", "MindSpore", "FGSM", "PGD", "CW", "云原生安全", "Kubernetes", "Docker", "容器镜像扫描", "MLOps"],
    47: ["内容安全", "机器召回模型", "安全策略", "SQL", "Scala", "Python", "Hadoop", "Spark", "大数据平台", "机器学习", "NLP", "计算机视觉", "多模态"],
    48: ["全栈开发", "AI Coding", "MCP", "CLI", "Skills", "Agent", "Vibe Coding", "Spec Coding", "AI", "LLM", "HTML5", "CSS3", "JavaScript", "TypeScript", "Vue", "React", "TRAE", "Claude Code", "Codex", "Cursor"],
    49: ["Java", "后端开发", "单元测试", "集成测试", "性能测试", "Spring Cloud Alibaba", "Dubbo", "MyBatis Plus", "Redis", "Elasticsearch", "CI/CD", "HTML", "CSS", "JavaScript", "Vue", "React", "大数据"],
    50: ["Java", "SQL", "Spring Boot", "Spring Cloud", "DDD", "Linux", "Docker", "窗口函数", "存储过程", "SQL调优", "Explain"],
    51: ["后端开发", "系统架构设计", "数据库设计", "接口设计", "单元测试", "性能调优", "Python", "C++", "Java", "Go", "Spring Boot", "Spring Cloud", "Redis", "OSS", "消息中间件", "MySQL", "Oracle", "云原生", "Docker", "Kubernetes", "领域建模"],
    52: ["后端开发", "Java", "Python", "Go", "Spring Boot", "Flask", "关系型数据库", "非关系型数据库", "时序数据库", "遥感", "GIS", "物联网"],
    53: ["高可用", "高并发", "CI/CD", "后端开发", "分布式系统", "Java", "Go", "C++", "JVM调优", "GC", "并发编程", "微服务", "Service Mesh", "分布式事务", "Seata", "Saga", "TiDB", "OceanBase", "Redis Cluster", "Kafka", "RocketMQ", "Kubernetes", "Istio", "Prometheus", "Grafana", "ELK"],
    54: ["AI应用", "LLM", "Agent", "TensorFlow", "PyTorch", "Dify", "MCP", "LangChain", "AutoGPT", "RAG", "Fine-tuning", "Java", "Python", "机器学习", "深度学习"],
    55: ["Java", "Spring Cloud", "Spring Boot", "MySQL", "SQL调优", "Nginx", "Prometheus", "Maven"],
    56: ["Vue 3", "前端开发", "后端开发", "Python", "Java", "Node.js", "接口开发", "中间件"],
    57: ["后端开发", "Python", "Java", "SQL", "MQTT", "Docker", "Redis", "Nginx", "数据库设计", "性能调优"],
    58: ["后端开发", "Java", "多线程", "性能调优", "Spring", "Spring Boot", "Spring Cloud", "Hibernate", "MyBatis", "JPA", "UML", "设计模式", "微服务", "混合云", "Docker", "Kubernetes", "CI/CD", "分布式系统", "Dubbo", "SQL", "NoSQL", "MySQL", "MongoDB", "SQL Server", "Oracle", "Redis", "RabbitMQ", "ELK", "Nginx", "高并发"],
    59: ["后端开发", "RESTful API", "Token授权", "高并发", "Redis", "分布式锁", "接口测试", "压力测试", "Java", "Spring Boot", "Spring Cloud", "C#", "ASP.NET Core", "Dapr", "SQL优化", "分布式数据库", "RabbitMQ", "Kafka", "微服务"],
    60: ["Java", "Spring Boot", "Spring Cloud", "MySQL", "Oracle", "SQL", "Redis", "Nginx", "Kafka", "Git", "Maven", "Jenkins", "禅道"],
    61: ["后端开发", "Java", "Python", "Go", "Spring Boot", "Flask", "关系型数据库", "非关系型数据库", "时序数据库", "遥感", "GIS", "物联网"],
    62: ["Java", "后端开发", "微服务", "API", "数据库设计", "SQL优化", "分布式事务", "消息队列", "AI编程助手", "Claude Code", "Cursor", "Codex", "Spec驱动开发", "CI/CD", "容器化", "Spring Boot", "Spring Cloud", "MySQL", "达梦数据库", "JVM性能调优", "分布式锁", "Vue 3", "TypeScript", "Vue Router", "Pinia", "Axios", "Element Plus", "Vite", "SCSS", "Tailwind CSS", "Linux", "Docker", "Kubernetes", "Git Flow", "单元测试"],
    63: ["Java", "多线程", "线程池", "反射", "注解", "JVM", "MVC", "Spring Boot", "Spring MVC", "MyBatis", "MyBatis-Plus", "Shiro", "J2EE", "MySQL", "MongoDB", "Redis", "Elasticsearch", "Logstash", "SQL调优", "Linux", "Tomcat", "Nginx", "Git", "Maven"],
    64: ["Java", "Spring", "MySQL", "接口调用", "数据库连接", "系统测试"],
    65: ["Java", "数据安全", "Spring Boot", "MyBatis", "MySQL性能调优", "高并发", "高可用"],
    66: ["基础模型", "模型API", "AI", "Linux", "Go", "C++", "Java", "Python", "RPC", "MySQL", "Redis", "Flink", "Hadoop", "Kafka"],
    67: ["大数据平台", "数据治理", "知识图谱", "运筹优化", "人工智能", "数据安全", "湖仓一体", "向量数据库", "AI原生数据平台", "分布式系统", "Hadoop", "Spark", "Flink", "Kafka", "云原生", "Kubernetes", "Docker", "Service Mesh", "机器学习", "特征平台", "模型训练平台", "模型推理", "MLflow", "KServe", "Ray", "工业互联网"],
}


def responsibility_block(number, jd):
    starts = ["【岗位职责】", "岗位职责：", "岗位职责:", "1.岗位职责", "工作职责", "职位描述：", "【职责描述】："]
    if number in (51, 56, 57):
        block = jd
    else:
        positions = [(jd.find(marker), marker) for marker in starts if jd.find(marker) >= 0]
        _, marker = min(positions)
        block = jd.split(marker, 1)[1]
    stops = ["\n【任职", "\n2.任职要求", "\n任职要求", "\n任职资格", "\n任职条件", "\n岗位要求", "\n【优先条件】", "\n薪酬福利"]
    stop_positions = [block.find(marker) for marker in stops if block.find(marker) >= 0]
    if stop_positions:
        block = block[:min(stop_positions)]
    lines = [line.strip().strip('"') for line in block.splitlines() if line.strip()]
    items = []
    current = None
    item_re = re.compile(r"^(?:[（(]\d+[）)]|\d+[.、]|-)\s*")
    for line in lines:
        if item_re.match(line):
            if current:
                items.append(current)
            current = item_re.sub("", line).strip()
        elif current:
            current += line
    if current:
        items.append(current)
    return [item.rstrip("；;") for item in items]


result = []
for row in ws.iter_rows(min_row=2, values_only=True):
    number = row[0]
    if not 35 <= number <= 67:
        continue
    title = row[6]
    corrected_responsibilities = responsibility_block(number, row[8])
    existing_skills = [x for x in (row[14] or "").split("|") if x]
    existing_responsibilities = [x for x in (row[15] or "").split("||") if x]
    issues = []
    if row[10] != title:
        issues.append(f"岗位名称应采用采集岗位标题：{title}")
    if existing_skills != skills[number]:
        issues.append("技能项存在漏标、泛化或规范化偏差，已按原文明示技术项重建")
    if existing_responsibilities != corrected_responsibilities:
        issues.append("职责存在漏项、拆分偏差或混入任职要求，已按岗位职责原编号重建")
    result.append({
        "sampleNumber": number,
        "title": title,
        "skills": skills[number],
        "responsibilities": corrected_responsibilities,
        "issues": issues,
    })

with open(output, "w", encoding="utf-8") as f:
    json.dump(result, f, ensure_ascii=False, indent=2)
