package com.zhigang.platform.analysis;

import com.zhigang.platform.domain.PlatformModels.ExtractedFact;
import com.zhigang.platform.domain.PlatformModels.SkillDefinition;
import com.zhigang.platform.repository.PlatformDataProvider;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.Optional;
import java.text.Normalizer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class SkillNormalizationService {
    private static final Pattern DATASET_BUILD_PATTERN = Pattern.compile("(?:创建|构建|制作).{0,16}数据集");
    private static final Map<String, List<String>> COMPETITION_ALIASES = Map.ofEntries(
            Map.entry("大语言模型", List.of("大模型", "LLM", "大型语言模型")),
            Map.entry("AI Agent", List.of("智能体", "Agentic", "Agent")),
            Map.entry("软件开发", List.of("软件工程")),
            Map.entry("模型微调", List.of("微调", "SFT")),
            Map.entry("分布式系统", List.of("分布式")),
            Map.entry("自然语言处理", List.of("NLP")),
            Map.entry("Prompt Engineering", List.of("提示词工程", "Prompt")),
            Map.entry("Kubernetes", List.of("K8s")),
            Map.entry("强化学习", List.of("RLHF")),
            Map.entry("人工智能", List.of("AI技术")),
            Map.entry("搜索算法", List.of("搜索引擎")),
            Map.entry("CI/CD", List.of("持续集成")),
            Map.entry("模型评测", List.of("大模型评测", "评测体系")),
            Map.entry("算法工程", List.of("算法研发")),
            Map.entry("本体建模", List.of("本体")),
            Map.entry("推荐算法", List.of("推荐系统")),
            Map.entry("JavaScript", List.of("JS"))
    );
    private static final Map<String, List<String>> STABLE_JD_ALIASES = Map.of(
            "模型微调", List.of("微调", "SFT"),
            "自然语言处理", List.of("NLP"),
            "强化学习", List.of("RLHF"),
            "推荐算法", List.of("推荐系统"),
            "分布式系统", List.of("分布式架构")
    );
    private static final Map<String, List<String>> RESUME_SKILLS = Map.ofEntries(
            Map.entry("Spring", List.of("Spring Framework")),
            Map.entry("Spring MVC", List.of("SpringMVC")),
            Map.entry("MyBatis", List.of("MyBatis-Plus")),
            Map.entry("Dubbo", List.of("Apache Dubbo")),
            Map.entry("RocketMQ", List.of("Rocket MQ")),
            Map.entry("Elasticsearch", List.of("ElasticSearch", "ES")),
            Map.entry("Nginx", List.of()),
            Map.entry("Tomcat", List.of()), Map.entry("Maven", List.of()),
            Map.entry("Git", List.of()), Map.entry("GitLab", List.of("Git Lab")),
            Map.entry("Binlog", List.of("MySQL Binlog")),
            Map.entry("C", List.of("C语言")), Map.entry("C++", List.of("Cpp")),
            Map.entry("STL", List.of()), Map.entry("Qt", List.of()), Map.entry("CMake", List.of()),
            Map.entry("RAII", List.of()), Map.entry("状态机", List.of()), Map.entry("面向对象", List.of()),
            Map.entry("嵌入式", List.of("嵌入式开发", "嵌入式系统")),
            Map.entry("Socket", List.of()), Map.entry("TCP/IP", List.of()), Map.entry("TCP", List.of()),
            Map.entry("UDP", List.of()), Map.entry("P2P", List.of()), Map.entry("KCP", List.of()),
            Map.entry("GDB", List.of()), Map.entry("Makefile", List.of()),
            Map.entry("交叉编译", List.of()), Map.entry("Wireshark", List.of()),
            Map.entry("OTA", List.of()), Map.entry("音视频", List.of("音视频开发")),
            Map.entry("Vue", List.of("Vue.js")), Map.entry("Vue 3", List.of("Vue3")),
            Map.entry("Nuxt", List.of("Nuxt.js")), Map.entry("Nuxt 4", List.of("Nuxt4")),
            Map.entry("TypeScript", List.of("TS")), Map.entry("JavaScript", List.of("JS")),
            Map.entry("CSS", List.of("CSS3")),
            Map.entry("HTML", List.of("HTML5")), Map.entry("SCSS", List.of()),
            Map.entry("Pinia", List.of()), Map.entry("Electron", List.of()), Map.entry("Axios", List.of()),
            Map.entry("UnoCSS", List.of()), Map.entry("NaiveUI", List.of("Naive UI")),
            Map.entry("SSR", List.of()), Map.entry("SSG", List.of()),
            Map.entry("响应式设计", List.of()), Map.entry("可访问性", List.of()),
            Map.entry("MQTT", List.of()), Map.entry("物联网", List.of("IoT", "AIoT")),
            Map.entry("传感器", List.of("传感器采集")), Map.entry("数据采集", List.of("采集管道", "数据采集中枢")),
            Map.entry("串口通信", List.of()),
            Map.entry("WiFi", List.of("Wi-Fi")), Map.entry("蓝牙", List.of("Bluetooth")),
            Map.entry("ESP32", List.of()), Map.entry("STM32", List.of()),
            Map.entry("RT-Thread", List.of("RTThread")), Map.entry("Android", List.of("Android Studio")),
            Map.entry("MATLAB", List.of()), Map.entry("分布式并行", List.of()),
            Map.entry("ZeRO", List.of()), Map.entry("FSDP", List.of()), Map.entry("Megatron", List.of()),
            Map.entry("DeepSpeed", List.of()), Map.entry("vLLM", List.of()), Map.entry("CANN", List.of()),
            Map.entry("Triton", List.of()), Map.entry("MPI", List.of()), Map.entry("NetCDF", List.of()),
            Map.entry("HDF5", List.of()), Map.entry("iOS", List.of()), Map.entry("Swift", List.of()),
            Map.entry("Objective-C", List.of("Objective C", "Obj-C")),
            Map.entry("AVFoundation", List.of()), Map.entry("VideoToolbox", List.of()),
            Map.entry("CoreMedia", List.of("Core Media")), Map.entry("RTSP", List.of()),
            Map.entry("HLS", List.of()), Map.entry("AirPlay", List.of()), Map.entry("DLNA", List.of()),
            Map.entry("WebRTC", List.of("Web RTC")), Map.entry("JitterBuffer", List.of("Jitter Buffer")),
            Map.entry("FEC", List.of()), Map.entry("Jenkins", List.of()), Map.entry("FFmpeg", List.of())
    );
    private static final List<String> JD_LITERAL_SKILLS = List.of(
            "大模型", "LLM", "Agent", "AI", "Transformer", "性能调优", "MLflow", "Ray", "MCP", "单元测试",
            "Spring Cloud", "SQL调优", "数据库设计", "Oracle", "TiDB", "OceanBase", "元数据管理", "数据建模",
            "特征工程", "智能体", "大数据", "湖仓一体", "Delta Lake", "运筹优化", "渗透测试", "大数据平台",
            "React", "Claude Code", "Codex", "Cursor", "关系型数据库", "非关系型数据库", "时序数据库", "遥感",
            "GIS", "高可用", "Service Mesh", "分布式事务", "ELK", "多线程", "RabbitMQ", "分布式锁", "SQL优化",
            "Go", "HBase", "Transwarp", "FusionInsight", "MaxCompute", "GaussDB", "实时计算", "对象存储", "ACK",
            "CCE", "TKE", "数据质量管理", "数据标准管理", "数据安全管理", "ETL", "时间序列预测", "异常检测",
            "分类", "回归", "聚类", "文本分析", "语义检索", "Prompt工程", "Pandas", "NumPy", "Scikit-learn",
            "数据平台", "数据可视化", "数据Agent", "AI自动化运维", "Doris", "数据采集", "数据模型", "数据质量监控",
            "云原生数据库", "存算分离", "向量数据", "列存", "HTAP", "Apache Iceberg", "Serverless", "混沌工程",
            "AI4DB", "DB4AI", "Rust", "io_uring", "RDMA", "Neon", "Supabase", "AWS Aurora", "HNSW", "DiskANN",
            "IVFFlat", "Vector DB", "数据管线", "对象存储分层", "冷热分层", "缓存策略", "数据压缩", "列式格式",
            "数据版本控制", "数据血缘", "可观测性", "DVC", "Data Catalog", "Dask", "FAISS", "Milvus",
            "PyTorch", "LlamaIndex", "深度学习", "机器学习", "强化学习", "自然语言处理", "高并发", "微服务",
            "SQL", "模型推理", "GPU", "CI/CD", "后端开发", "数据仓库", "数据清洗", "数据存储", "数据库自调优"
    );
    private static final Set<String> CONTEXTUAL_JD_LITERAL_SKILLS = Set.of(
            "SQL", "SQL优化", "Go", "软件开发");
    private static final Set<String> REVIEWED_LITERAL_SKILLS = Set.of(("""
            6G安全|AB实验|ACK|AI Agent|AI HUB|AI4DB|AI原生数据平台|AI自动化运维|AWS Aurora|Airflow|Apache Iceberg|Argo|Axios|C#|C++|CCE|CI/CD|CSS3|CUDA|CW|Codex|Cursor|DB4AI|DDD|DNS安全|DVC|Dapr|Dask|Data Catalog|Delta Lake|DevOps|DiskANN|Docker|Dubbo|ELK|ETL|Elasticsearch|Element Plus|Embedding|Explain|FAISS|FGSM|FastAPI|Flask|Flink|FusionInsight|GIS|GPU|GaussDB|Git|Git Flow|Grafana|HBase|HTAP|HTML5|Hadoop|Helm|Hibernate|Hive|Horovod|IPv6安全|IVFFlat|Istio|J2EE|JPA|JVM性能调优|JVM调优|Java|JavaScript|Jenkins|KServe|Kafka|Kubeflow|Kubernetes|Kustomize|LLMOps|LangChain|LayoutParser|Linux|Linux脚本|LlamaIndex|Logstash|MLOps|MLflow|MQTT|MVC|MaaS|Maven|MaxCompute|Milvus|MindSpore|MongoDB|MyBatis|MyBatis Plus|MyBatis-Plus|MySQL|Neon|Nginx|NoSQL|Node.js|NumPy|OSS|OceanBase|Oracle|PGD|PaddleOCR|Pandas|Pinia|PostgreSQL|Prometheus|Prompt Engineering|PyTorch|Python|Qdrant|RAG|RESTful API|RPC|RabbitMQ|Ray|Redis|Redis Cluster|RocketMQ|SCSS|SQL Server|SQL调优|Saga|Seata|Serverless|Service Mesh|Shell|Spark|Spring Boot|Spring Cloud|Spring Cloud Alibaba|Supabase|TKE|TensorFlow|TensorFlow Serving|Text Generation Inference|TiDB|Tomcat|Transformer|Transwarp|TypeScript|UML|Vector DB|Vite|Vue 3|Vue Router|io_uring|云原生|云原生安全|云原生数据库|人工智能|元数据管理|内容安全|冷热分层|分布式事务|分布式架构|分布式系统|分布式计算|分布式调度|分布式锁|列存|列式格式|单元测试|压力测试|反射|后端开发|后门检测|向量数据库|向量检索|在线推理|基础模型|多模态|多线程|大语言模型|存储过程|存算分离|安全大模型|容器镜像扫描|对抗攻击|对抗训练|对象存储分层|差分隐私|并发编程|异构计算|强化学习|微服务|性能测试|接口开发|接口测试|接口调用|推荐算法|数据Agent|数据仓库|数据压缩|数据可视化|数据安全|数据安全管理|数据库设计|数据库连接|数据标准管理|数据标注|数据治理|数据流水线|数据湖|数据版本控制|数据管线|数据脱敏|数据质量监控|数据质量管理|文本分析|时序数据库|时间序列预测|机器召回模型|机器学习|模型即服务|模型安全|模型微调|模型推理|模型管理|模型蒸馏|模型训练平台|模型评测|模型鲁棒性|注解|测试开发|深度学习|混沌工程|渗透测试|湖仓一体|物联网|知识图谱|禅道|移动通信网络安全|窗口函数|算力网络安全|线程池|统计分析|缓存策略|网络与信息安全|网络安全攻防|自动化测试|自然语言处理|语义检索|调度引擎|路由安全|边缘计算|遥感|集成测试|非关系型数据库|领域建模|高并发
            """).trim().split("\\|"));
    private final PlatformDataProvider dataProvider;
    private volatile AliasIndex aliasIndex;

    public SkillNormalizationService(PlatformDataProvider dataProvider) {
        this.dataProvider = dataProvider;
    }

    public List<ExtractedFact> extract(String text) {
        AliasIndex index = index();
        String lower = compatibilityNormalized(text).toLowerCase(Locale.ROOT);
        Map<String, ExtractedFact> facts = new LinkedHashMap<>();
        for (AliasEntry entry : index.entries()) {
            int from = 0;
            while (from < lower.length()) {
                MatchSpan match = findAliasMatch(lower, entry.alias(), from);
                if (match == null) break;
                int position = match.start();
                from = match.end();
                if (!isContextuallyValid(text, entry, position)) continue;
                String raw = text.substring(position, match.end());
                if (isShadowedByLongerSkill(entry, raw, lower, position, index.entries())) continue;
                String requirementType = detectRequirementType(text, position);
                double confidence = entry.exactCanonical() ? 0.99 : 0.96;
                ExtractedFact candidate = new ExtractedFact(
                        "SKILL", raw, entry.canonical(), requirementType, position,
                        position + raw.length(), confidence, "NORMALIZED"
                );
                facts.merge(entry.canonical(), candidate, (existing, replacement) ->
                        preferredOccurrence(text, existing, replacement));
            }
        }
        Matcher datasetBuild = DATASET_BUILD_PATTERN.matcher(text);
        if (datasetBuild.find()) {
            String raw = datasetBuild.group();
            facts.putIfAbsent("数据集构建", new ExtractedFact(
                    "SKILL", raw, "数据集构建", detectRequirementType(text, datasetBuild.start()),
                    datasetBuild.start(), datasetBuild.end(), 0.96, "NORMALIZED"));
        }
        return new ArrayList<>(facts.values());
    }

    /** Normalize one-to-one compatibility glyphs while preserving source offsets. */
    private String compatibilityNormalized(String value) {
        StringBuilder normalized = new StringBuilder(value.length());
        value.codePoints().forEach(codePoint -> {
            String source = new String(Character.toChars(codePoint));
            String mapped = Normalizer.normalize(source, Normalizer.Form.NFKC);
            normalized.append(mapped.codePointCount(0, mapped.length()) == 1 ? mapped : source);
        });
        return normalized.toString();
    }

    private ExtractedFact preferredOccurrence(String text, ExtractedFact existing, ExtractedFact replacement) {
        int existingScore = occurrenceEvidenceScore(text, existing);
        int replacementScore = occurrenceEvidenceScore(text, replacement);
        if (replacementScore != existingScore) return replacementScore > existingScore ? replacement : existing;
        return replacement.confidence() > existing.confidence() ? replacement : existing;
    }

    private int occurrenceEvidenceScore(String text, ExtractedFact fact) {
        if ("NEGATED".equals(fact.requirementType())) return -100;
        int lineStart = Math.max(text.lastIndexOf('\n', fact.startOffset()),
                text.lastIndexOf('\r', fact.startOffset())) + 1;
        int nextLf = text.indexOf('\n', fact.endOffset());
        int nextCr = text.indexOf('\r', fact.endOffset());
        int lineEnd = nextLf < 0 ? text.length() : nextLf;
        if (nextCr >= 0) lineEnd = Math.min(lineEnd, nextCr);
        String line = text.substring(lineStart, lineEnd).toLowerCase(Locale.ROOT);
        int score = 0;
        if (line.matches(".*(负责|构建|开发|实现|优化|部署|上线|生产|容器化|使用|built|developed|implemented|deployed|optimized).*")) {
            score += 10;
        }
        if (line.matches(".*(提升|降低|通过|完成|%|测试|验收|improved|reduced|passed).*")) score += 5;
        return score;
    }

    private MatchSpan findAliasMatch(String text, String alias, int from) {
        int searchFrom = from;
        while (from < text.length()) {
            int position = text.indexOf(alias, from);
            if (position < 0) break;
            int end = position + alias.length();
            boolean leftValid = !isAsciiWord(alias.charAt(0))
                    || position == 0 || !isAsciiWord(text.charAt(position - 1));
            boolean rightValid = !isAsciiWord(alias.charAt(alias.length() - 1))
                    || end == text.length() || !isAsciiWord(text.charAt(end));
            if (leftValid && rightValid) return new MatchSpan(position, end);
            from = position + 1;
        }
        if (alias.indexOf(' ') >= 0) {
            String expression = String.join("\\s+", alias.trim().split("\\s+"));
            Matcher matcher = Pattern.compile(expression).matcher(text);
            matcher.region(Math.min(searchFrom, text.length()), text.length());
            while (matcher.find()) {
                int position = matcher.start();
                int end = matcher.end();
                boolean leftValid = position == 0 || !isAsciiWord(text.charAt(position - 1));
                boolean rightValid = end == text.length() || !isAsciiWord(text.charAt(end));
                if (leftValid && rightValid) return new MatchSpan(position, end);
            }
        }
        if (alias.indexOf('-') >= 0) {
            String expression = String.join("-\\s*", java.util.Arrays.stream(alias.split("-", -1))
                    .map(Pattern::quote).toList());
            Matcher matcher = Pattern.compile(expression).matcher(text);
            matcher.region(Math.min(searchFrom, text.length()), text.length());
            while (matcher.find()) {
                int position = matcher.start();
                int end = matcher.end();
                boolean leftValid = position == 0 || !isAsciiWord(text.charAt(position - 1));
                boolean rightValid = end == text.length() || !isAsciiWord(text.charAt(end));
                if (leftValid && rightValid) return new MatchSpan(position, end);
            }
        }
        return null;
    }

    private boolean isAsciiWord(char value) {
        return value >= 'a' && value <= 'z' || value >= '0' && value <= '9';
    }

    private boolean startsWithAny(String text, int position, String... candidates) {
        for (String candidate : candidates) {
            if (text.startsWith(candidate, position)) return true;
        }
        return false;
    }

    private boolean isShadowedByLongerSkill(AliasEntry entry, String raw, String lower, int position,
                                            List<AliasEntry> entries) {
        if (isInsideLongerAlias(entry, lower, position, entries)) return true;
        String canonical = entry.canonical();
        if (canonical.equals("API")) {
            int start = Math.max(0, position - 20);
            String before = lower.substring(start, position);
            if (before.matches("(?s).*(rest|openai|llm|大模型|google maps|shopify)\\s*")) return true;
        }
        if (canonical.equals("LLM")) {
            String after = lower.substring(Math.min(lower.length(), position + entry.alias().length()),
                    Math.min(lower.length(), position + entry.alias().length() + 8));
            if (after.matches("(?s)^\\s*api.*")) return true;
        }
        if (canonical.equals("JUnit")) {
            String after = lower.substring(Math.min(lower.length(), position + entry.alias().length()),
                    Math.min(lower.length(), position + entry.alias().length() + 8));
            if (after.matches("(?s)^\\s+xml.*")) return true;
        }
        if (canonical.equals("XML")) {
            String before = lower.substring(Math.max(0, position - 12), position);
            if (before.matches("(?s).*junit\\s*")) return true;
        }
        if (canonical.equals("Spring")) {
            return !raw.equalsIgnoreCase("Spring") || startsWithAny(lower, position,
                    "spring boot", "spring-boot", "spring cloud", "spring mvc");
        }
        if (canonical.equals("Spring Cloud")) {
            return lower.startsWith("spring cloud alibaba", position);
        }
        if (canonical.equals("MyBatis")) {
            return startsWithAny(lower, position, "mybatis plus", "mybatis-plus");
        }
        if (canonical.equals("ZeRO")) {
            return !raw.equals("ZeRO");
        }
        if (canonical.equals("OpenAI")) {
            String before = lower.substring(Math.max(0, position - 12), position).replaceAll("\\s+", " ");
            String after = lower.substring(Math.min(lower.length(), position + entry.alias().length()),
                    Math.min(lower.length(), position + entry.alias().length() + 8)).replaceAll("\\s+", " ");
            return before.matches(".*azure\\s*$") || after.matches("^\\s*api.*");
        }
        if (canonical.equals("Google Cloud")) {
            String after = lower.substring(Math.min(lower.length(), position + entry.alias().length()),
                    Math.min(lower.length(), position + entry.alias().length() + 8)).replaceAll("\\s+", " ");
            return after.matches("^\\s*tpu.*");
        }
        return canonical.equals("SQL") && startsWithAny(lower, position,
                "sql优化", "sql调优", "sql server");
    }

    private boolean isInsideLongerAlias(AliasEntry entry, String text, int position, List<AliasEntry> entries) {
        int end = position + entry.alias().length();
        for (AliasEntry candidate : entries) {
            if (candidate.alias().length() <= entry.alias().length()) continue;
            int firstStart = Math.max(0, end - candidate.alias().length());
            for (int candidateStart = firstStart; candidateStart <= position; candidateStart++) {
                int candidateEnd = candidateStart + candidate.alias().length();
                if (candidateEnd >= end && text.startsWith(candidate.alias(), candidateStart)) {
                    if (entry.canonical().equals("响应式设计") && candidate.canonical().equals("CSS")) continue;
                    return true;
                }
            }
        }
        return false;
    }

    private boolean isContextuallyValid(String text, AliasEntry entry, int position) {
        String alias = entry.alias();
        String normalized = alias.toLowerCase(Locale.ROOT);
        String productCanonical = contextualCanonical(ResumeSkillVocabulary.PRODUCT_CONTEXTUAL_ALIASES, alias);
        if (productCanonical != null) {
            boolean hasProductContext = hasNearbyContext(text, position, alias.length(),
                    "用户反馈|业务数据|需求分析|prd|产品");
            if (entry.canonical().equals(productCanonical)) return hasProductContext;
            if (hasProductContext) return false;
        }
        String testingCanonical = contextualCanonical(ResumeSkillVocabulary.TESTING_CONTEXTUAL_ALIASES, alias);
        if (testingCanonical != null) {
            if (isDocumentProcessingDisclaimer(text, position, alias.length())) return false;
            boolean hasTestingContext = hasNearbyContext(text, position, alias.length(),
                    "软件测试|测试经验|测试工程|全端测试|测试用例|测试脚本|设计用例|测试|自动化脚本");
            if (entry.canonical().equals(testingCanonical)) return hasTestingContext;
            if (hasTestingContext) return false;
        }
        if (normalized.equals("go")) {
            int start = Math.max(0, position - 80);
            int end = Math.min(text.length(), position + alias.length() + 12);
            return text.substring(start, end).replace('\r', ' ').replace('\n', ' ')
                    .toLowerCase(Locale.ROOT)
                    .matches(".*(熟悉\\s*go|掌握\\s*go|精通\\s*go|go\\s*.{0,6}(语言|开发|工程)|(技术技能|编程语言|languages?|skills?).{0,60}go).*");
        }
        if (normalized.equals("软件工程")) {
            int start = Math.max(0, position - 12);
            int end = Math.min(text.length(), position + alias.length() + 12);
            String context = text.substring(start, end).replace('\r', ' ').replace('\n', ' ');
            return context.matches(".*软件工程.{0,8}(经验|能力|背景|实践|体系).*");
        }
        if (entry.canonical().equals("GitHub")) {
            int lineStart = Math.max(text.lastIndexOf('\n', position), text.lastIndexOf('\r', position)) + 1;
            int nextLf = text.indexOf('\n', position);
            int nextCr = text.indexOf('\r', position);
            int lineEnd = nextLf < 0 ? text.length() : nextLf;
            if (nextCr >= 0) lineEnd = Math.min(lineEnd, nextCr);
            String line = text.substring(lineStart, lineEnd).toLowerCase(Locale.ROOT);
            int previousLineStart = lineStart == 0 ? 0
                    : Math.max(text.lastIndexOf('\n', Math.max(0, lineStart - 2)),
                    text.lastIndexOf('\r', Math.max(0, lineStart - 2))) + 1;
            String previousLine = text.substring(previousLineStart, Math.max(previousLineStart, lineStart - 1))
                    .toLowerCase(Locale.ROOT);
            int end = position + alias.length();
            String following = text.substring(end, Math.min(text.length(), end + 32)).toLowerCase(Locale.ROOT);
            return !line.contains("github.com/") && !line.matches(".*(原始公开资料|原始来源|source).*github.*")
                    && !(line.trim().startsWith("github.com/") && previousLine.matches(".*github[：:]?\\s*$"))
                    && !following.matches("(?s)^[：:]?\\s*github\\.com/.*")
                    && !following.startsWith(" actions");
        }
        if (!Set.of("ai", "agent", "llm", "sql").contains(normalized)) return true;
        int contextRadius = normalized.equals("sql") ? 180 : 36;
        int start = Math.max(0, position - contextRadius);
        int end = Math.min(text.length(), position + alias.length() + contextRadius);
        String context = text.substring(start, end).replace('\r', ' ').replace('\n', ' ');
        return context.toLowerCase(Locale.ROOT).matches(
                ".*(任职要求|岗位要求|专业技能|skills?|technologies|tools|languages|熟悉|掌握|精通|使用|基于|开发|框架|语言|系统|模型|技术|算法|工程|数据库|查询|优化).*" );
    }

    private boolean isDocumentProcessingDisclaimer(String text, int position, int aliasLength) {
        int lineStart = Math.max(text.lastIndexOf('\n', position), text.lastIndexOf('\r', position)) + 1;
        int nextLf = text.indexOf('\n', position + aliasLength);
        int nextCr = text.indexOf('\r', position + aliasLength);
        int lineEnd = nextLf < 0 ? text.length() : nextLf;
        if (nextCr >= 0) lineEnd = Math.min(lineEnd, nextCr);
        String line = text.substring(lineStart, lineEnd);
        return line.matches(".*文件.*(?:仅用于|用于).*(?:解析|排版).*测试.*");
    }

    private String contextualCanonical(Map<String, List<String>> aliases, String alias) {
        return aliases.entrySet().stream()
                .filter(entry -> entry.getValue().stream().anyMatch(alias::equalsIgnoreCase))
                .map(Map.Entry::getKey)
                .findFirst()
                .orElse(null);
    }

    private boolean hasNearbyContext(String text, int position, int aliasLength, String markers) {
        int lineStart = Math.max(text.lastIndexOf('\n', position), text.lastIndexOf('\r', position)) + 1;
        int nextLf = text.indexOf('\n', position + aliasLength);
        int nextCr = text.indexOf('\r', position + aliasLength);
        int lineEnd = nextLf < 0 ? text.length() : nextLf;
        if (nextCr >= 0) lineEnd = Math.min(lineEnd, nextCr);
        int clauseStart = position;
        while (clauseStart > lineStart && !isContextDelimiter(text.charAt(clauseStart - 1))) clauseStart--;
        int clauseEnd = position + aliasLength;
        while (clauseEnd < lineEnd && !isContextDelimiter(text.charAt(clauseEnd))) clauseEnd++;
        int start = Math.max(clauseStart, position - 180);
        int end = Math.min(clauseEnd, position + aliasLength + 180);
        String context = text.substring(start, end);
        return context.toLowerCase(Locale.ROOT).matches(".*(" + markers + ").*");
    }

    private boolean isContextDelimiter(char value) {
        return value == '\uFF1B' || value == ';'
                || value == '\u3002' || value == '.' || value == '\uFF01' || value == '!'
                || value == '\uFF1F' || value == '?' || value == '\n' || value == '\r';
    }

    public Map<String, List<String>> prerequisiteGraph() {
        Map<String, List<String>> graph = new HashMap<>();
        dataProvider.findSkills().forEach(skill -> graph.put(skill.name(), skill.prerequisites()));
        return graph;
    }

    public Optional<String> canonicalize(String value) {
        if (value == null || value.isBlank()) return Optional.empty();
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return index().entries().stream().filter(entry -> entry.alias().equals(normalized))
                .map(AliasEntry::canonical).findFirst();
    }

    public Optional<String> canonicalizeSkillName(String value) {
        if (looksLikeResponsibility(value)) return Optional.empty();
        return canonicalize(value);
    }

    public boolean isReviewedSkill(String rawValue, String normalizedValue) {
        if (rawValue == null || rawValue.isBlank() || normalizedValue == null || normalizedValue.isBlank()) return false;
        Optional<String> rawCanonical = canonicalize(rawValue);
        Optional<String> normalizedCanonical = canonicalize(normalizedValue);
        return rawCanonical.isPresent() && normalizedCanonical.isPresent()
                && rawCanonical.get().equalsIgnoreCase(normalizedCanonical.get());
    }

    private String detectRequirementType(String text, int skillPosition) {
        int start = skillPosition;
        while (start > 0 && !isClauseDelimiter(text.charAt(start - 1))) start--;
        int end = skillPosition;
        while (end < text.length() && !isClauseDelimiter(text.charAt(end))) end++;
        String context = text.substring(start, end);
        if (context.matches(".*(不要求|无需|可不会|不需要).*")) {
            return "NEGATED";
        }
        if (context.matches(".*(优先|加分|了解).*")) {
            return "BONUS";
        }
        if (context.matches(".*(必须|精通|熟练|掌握|要求).*")) {
            return "REQUIRED";
        }
        return "MENTIONED";
    }

    private boolean isClauseDelimiter(char value) {
        return value == '。' || value == '；' || value == ';' || value == '，' || value == ',' || value == '\n' || value == '\r';
    }

    private boolean looksLikeResponsibility(String value) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.length() > 40 || normalized.split("\\s+").length > 5) return true;
        String lower = normalized.toLowerCase(Locale.ROOT);
        return lower.matches(".*\\b(build|design|develop|implement|optimize|own|deliver|maintain|负责|构建|设计|开发|实现|优化|交付|维护)\\b.*");
    }

    private AliasIndex index() {
        AliasIndex current = aliasIndex;
        if (current == null) {
            synchronized (this) {
                current = aliasIndex;
                if (current == null) {
                    List<AliasEntry> entries = new ArrayList<>();
                    RESUME_SKILLS.forEach((canonical, aliases) -> {
                        String reviewedCanonical = ResumeSkillVocabulary.canonicalName(canonical);
                        entries.add(new AliasEntry(canonical.toLowerCase(Locale.ROOT), reviewedCanonical,
                                canonical.equalsIgnoreCase(reviewedCanonical)));
                        aliases.forEach(alias -> entries.add(new AliasEntry(
                                alias.toLowerCase(Locale.ROOT), reviewedCanonical, false)));
                    });
                    STABLE_JD_ALIASES.forEach((canonical, aliases) -> {
                        String reviewedCanonical = ResumeSkillVocabulary.canonicalName(canonical);
                        entries.add(new AliasEntry(canonical.toLowerCase(Locale.ROOT), reviewedCanonical,
                                canonical.equalsIgnoreCase(reviewedCanonical)));
                        aliases.forEach(alias -> entries.add(new AliasEntry(
                                alias.toLowerCase(Locale.ROOT), reviewedCanonical, false)));
                    });
                    REVIEWED_LITERAL_SKILLS.forEach(skill -> {
                        String reviewedCanonical = ResumeSkillVocabulary.canonicalName(skill);
                        entries.add(new AliasEntry(skill.toLowerCase(Locale.ROOT), reviewedCanonical,
                                skill.equalsIgnoreCase(reviewedCanonical)));
                    });
                    ResumeSkillVocabulary.LITERALS.forEach(skill -> {
                        String reviewedCanonical = ResumeSkillVocabulary.canonicalName(skill);
                        entries.add(new AliasEntry(skill.toLowerCase(Locale.ROOT), reviewedCanonical,
                                skill.equalsIgnoreCase(reviewedCanonical)));
                    });
                    ResumeSkillVocabulary.ALIASES.forEach((canonical, aliases) -> aliases.forEach(alias ->
                            entries.add(new AliasEntry(alias.toLowerCase(Locale.ROOT), canonical, false))));
                    ResumeSkillVocabulary.PRODUCT_CONTEXTUAL_ALIASES.forEach((canonical, aliases) ->
                            aliases.forEach(alias -> entries.add(new AliasEntry(
                                    alias.toLowerCase(Locale.ROOT), canonical, false))));
                    ResumeSkillVocabulary.TESTING_CONTEXTUAL_ALIASES.forEach((canonical, aliases) ->
                            aliases.forEach(alias -> entries.add(new AliasEntry(
                                    alias.toLowerCase(Locale.ROOT), canonical, false))));
                    CONTEXTUAL_JD_LITERAL_SKILLS.forEach(skill -> entries.add(new AliasEntry(
                            skill.toLowerCase(Locale.ROOT), skill, true)));
                    ResumeSkillMarketVocabulary.ALIASES.forEach((canonical, aliases) -> {
                        entries.add(new AliasEntry(canonical.toLowerCase(Locale.ROOT), canonical, true));
                        aliases.forEach(alias -> entries.add(new AliasEntry(
                                alias.toLowerCase(Locale.ROOT), canonical, false)));
                    });
                    entries.add(new AliasEntry("kafka", "Kafka", true));
                    for (SkillDefinition skill : dataProvider.findSkills()) {
                        String reviewedCanonical = ResumeSkillVocabulary.canonicalName(skill.name());
                        Set<String> aliases = new java.util.LinkedHashSet<>();
                        aliases.add(skill.name());
                        skill.aliases().stream()
                                .filter(alias -> !isReviewedLiteral(alias))
                                .forEach(aliases::add);
                        COMPETITION_ALIASES.getOrDefault(skill.name(), List.of()).stream()
                                .filter(alias -> !Set.of("Agent", "智能体", "Spring").contains(alias))
                                .forEach(aliases::add);
                        aliases.forEach(alias -> entries.add(new AliasEntry(
                                alias.toLowerCase(Locale.ROOT), reviewedCanonical,
                                alias.equalsIgnoreCase(reviewedCanonical))));
                    }
                    Map<String, AliasEntry> uniqueAliases = new LinkedHashMap<>();
                    entries.forEach(entry -> {
                        String key = Set.of("git/github", "c/c++").contains(entry.alias())
                                || ResumeSkillVocabulary.isContextualAlias(entry.alias(), entry.canonical())
                                ? entry.alias() + "\u0000" + entry.canonical()
                                : entry.alias();
                        uniqueAliases.putIfAbsent(key, entry);
                    });
                    List<AliasEntry> orderedEntries = new ArrayList<>(uniqueAliases.values());
                    orderedEntries.sort(Comparator.comparingInt((AliasEntry entry) -> entry.alias().length()).reversed());
                    current = new AliasIndex(List.copyOf(orderedEntries));
                    aliasIndex = current;
                }
            }
        }
        return current;
    }

    private boolean isReviewedLiteral(String value) {
        return REVIEWED_LITERAL_SKILLS.stream().anyMatch(value::equalsIgnoreCase);
    }

    private record AliasIndex(List<AliasEntry> entries) {
    }

    private record AliasEntry(String alias, String canonical, boolean exactCanonical) {
    }

    private record MatchSpan(int start, int end) {
    }
}
