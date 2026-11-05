package com.zhigang.platform.analysis;

import java.util.List;
import java.util.Map;
import java.util.Set;

final class ResumeSkillVocabulary {
    static final Map<String, String> CANONICAL_NAMES = Map.of(
            "Vue 3", "Vue",
            "Nuxt 4", "Nuxt",
            "NaiveUI", "Naive UI",
            "WiFi", "Wi-Fi",
            "RESTful API", "REST API",
            "CSS3", "CSS",
            "自然语言处理", "NLP",
            "模型微调", "微调",
            "大语言模型", "LLM",
            "Transformers", "Transformer"
    );

    static final Set<String> LITERALS = Set.of(("""
            .NET|3D 感知|5 Whys|AB3DMOT|ABAC|Active Directory|AEO|AES|AG Grid|Agile|AI Agent|AirPlay|Ajax|Allure|Android|Android Testing|Angular|Apache Tika|API|API Gateway|API Testing|App Testing|AraBERT|ArcGIS|Arduino|ARIMA|ARM|Ascend C|Assembly|Astro|AT89C52|AVFoundation|AWS|AWS Lambda|AWS S3|Axios|Azure DevOps|Axum|Bash|BentoML|BERT|BERT-LDA|BEV|Binlog|Bitbucket Pipelines|BM25|Boost|Boost.Asio|Bootstrap|BPMN|Bun|BVA|C|C#|C++|CANN|CenterPoint|CentOS|Charles|Chart.js|ChromaDB|CI/CD|CLI|Claude Code|Clean Architecture|CloudWatch|cloud-init|CMake|CNN|CodeIgniter|CODESYS|Compatibility Testing|CoreMedia|COSO|Create React App|CRM|CRUD|CSV|CSS|Cypress|Dagster|DAO|Dart|Datadog|DAX|dbt|DBeaver|DDD|DeepSeek|DeepSpeed|Delphi|DETR|DFD|DGCNN|Django REST Framework|DLNA|DoA|Docker|DRM|Dubbo|DVC|DynamoDB|ECDH|ECharts|Elasticsearch|Electron|Element Plus|Element UI|Elixir|ERP|ERPNext|ESP32|ETL|Eval|Excel|Factory I/O|FastAPI|FEC|Fetch|Fiddler|Figma|Firebase|Flask|Flutter|FlutterFlow|FMCW Radar|Frappe|FSDP|G-code|Gazebo|GCD|GDB|Gemini|Git|GitHub|GitHub Actions|GitLab|GitLab CI/CD|Go|Google Cloud|Google Maps API|Google OAuth|GPIO|GPS|GPT-2|GPT-3|GPT-4|Gradle|Grafana|GraphQL|gRPC|H5 Testing|Hadoop|HDF5|HDR|HiAgent|Hibernate|HLS|HQL|HTML|Hugging Face|IaC|ICP|i18next|IMM|IMU|InfluxDB|INT8 量化|iOS|iOS Runtime|iOS Testing|Jasmine|Java|JavaScript|JDBC|Jenkins|Jest|Jira|JitterBuffer|JUnit XML|Jupyter|JWT|Kalman|Karma|KCP|Keycloak|Kickstart|Kind|KNN|Kubernetes|LangChain|LangGraph|Laravel|LaTeX|LCD1602|Levenshtein|LiDAR|LightGBM|Linux|LLaMA-Factory|LlamaIndex|LLM|LLM API|LLM-as-a-Judge|LLM评测|LocalStorage|LoRA|LSTM|Lua|Makefile|Markdown|Mastra|MATLAB|Matplotlib|Maven|MCP|MediaPipe|Megatron|MERN|MeterSphere|MFCC|Microsoft Access|MindSpore|Mixpanel|MongoDB|MongoDB Atlas|MPI|MQTT|Mstar|MuJoCo|MVC|MVI|MVVM|MyBatis|MySQL|Naive Bayes|Naive UI|Navicat|NetCDF|NewsAPI|Next.js|Nginx|NLP|NLTK|Node-RED|Node.js|npm|NumPy|Nuxt|Objective-C|OCR|ONNX|OpenAI|OpenAI API|OpenAPI|OpenCV|OTA|OWL|P2P|P50/P95/P99|Pandas|PCA|PEFT|Performance Testing|Photoshop|PHP|Pinia|PL/SQL|Playwright|PLC|Plotly|Podman|POI检索|PostgreSQL|Postman|Power BI|Power Query|PowerShell|PP-LiteSeg|PRD|ProcessingJS|Prometheus|Prompt Engineering|Protocol Buffers|PTZ|PV-RCNN|PWM|PX4|PySpark|Python|PyTorch|QLoRA|Qt|Quartz|R|RabbitMQ|RAG|RAII|RANSAC|Raspberry Pi|Razor|RBF|RBAC|RCM|RDF|React|React Router|RealSense|Redis|Redfish|Redux Toolkit|Regression Testing|Requests|REST API|RF-DETR|RGB|Rockchip RK3566|RocketMQ|Rocky Linux|Room|RPM|RSA|RSS|RT-Thread|RTP|RTSP|Ruby on Rails|RunLoop|Rust|SAM|SciPy|Scikit-learn|Scrapy|Scrum|SCSS|SDLC|Sentry|SEO|SFML|SHA-256|SHAP|Shell|Shopify API|Slimefun4|SMTP|Socket|SolidWorks|SORT|SPA|SpaCy|Spark|SPSS|Spring|Spring AI|Spring Boot|Spring Data JPA|Spring MVC|SQL|SQL Server|SQLite|SRM|SRS|SSE|SSG|SSH|SSR|Stable Diffusion|STL|STM32|Storybook|Streamlit|Supabase|Svelte|SVM|Swift|systemd|Tailwind CSS|TanStack Query|TanStack Virtual|Taro|TCP|TCP/IP|Telegraf|Tekton|TensorFlow|TensorRT|Terraform|Three.js|Tiny ImageNet|TinyLlama|Tomcat|TPS|Transformer|Transformers|Treap|Triton|TypeScript|Ucos II|UDP|UI Testing|UI/UX|UML|U-Net|uni-app|Unity|Unix|UnoCSS|Unreal Engine|Use Case|Vercel AI SDK|veRL|VideoToolbox|Vision Transformer|Vite|VLA|vLLM|VPN|VS Code|Vue|Web Testing|Webpack|WebRTC|WebSocket|Wi-Fi|Windows Server|Windows Socket|Wireshark|WSL2|XAMPP|YOLO|ZeRO|Zivid|串口通信|二维码译码|交叉编译|交互设计|传感器|传感器融合|分布式并行|剪枝|功能测试|区块链|区间树|原型设计|可观测性|可访问性|响应式设计|国际化|多智能体|多目标跟踪|多视图几何|场景法|嵌入式|工作流自动化|微信小程序|微服务|微调|情景分析|插件化|数据加密|数据建模|数据校验|数据清洗|数据结构与算法|数据管道|数据质量|数据集构建|数据库测试|数据驱动测试|星型模型|机器学习|机器视觉|桶排序|概率建模|混合检索|渗透测试|点云|物联网|特征工程|状态机|相机标定|百度千帆|知识图谱|知识蒸馏|策略模式|组件化|结构化数据|网络安全|聊天机器人|腾讯云|自动化|自动化测试|表单校验|超分辨率|透视分析|列表虚拟化|弱网测试|智能语音|蓝牙|视觉 SLAM|语义分割|错误推测法|防火墙|随机森林|分页|等价类|需求分析|零知识证明|面向对象|音视频|预训练|飞书 Aily|鱼骨图|探索性数据分析
            """).trim().split("\\|"));

    static final Map<String, List<String>> ALIASES = Map.ofEntries(
            Map.entry("AG Grid", List.of("Ag-Grid")),
            Map.entry("Agile", List.of("Agile/Scrum")),
            Map.entry("AI Agent", List.of("Agent", "AI agents", "agent development",
                    "multi-agent orchestration", "采购知识库 Agent", "多Agent", "多智能体",
                    "工具编排")),
            Map.entry("聊天机器人", List.of("Chatbot")),
            Map.entry("Android", List.of("Android Studio", "Android 应用基础")),
            Map.entry("Apache Tika", List.of("Tika")),
            Map.entry("C", List.of("C 语言", "C/C++")),
            Map.entry("C++", List.of("C++17", "C/C++")),
            Map.entry("CI/CD", List.of("Jenkins/Git CI/CD")),
            Map.entry("Create React App", List.of("CRA")),
            Map.entry("CSS", List.of("CSS3响应式布局", "CSS3 响应式布局")),
            Map.entry("Dart", List.of("Flutter/Dart")),
            Map.entry("Excel", List.of("Advanced Excel")),
            Map.entry("Flutter", List.of("Flutter/Dart")),
            Map.entry("Git", List.of("Git/GitHub", "Jenkins/Git CI/CD")),
            Map.entry("GitHub", List.of("Git/GitHub")),
            Map.entry("Google Cloud", List.of("GCP")),
            Map.entry("HTML", List.of("HTML 网页设计")),
            Map.entry("JavaScript", List.of("JavaScript/Node.js", "JavaScript/TypeScript")),
            Map.entry("Jenkins", List.of("Jenkins/Git CI/CD")),
            Map.entry("LLM API", List.of("大模型 API")),
            Map.entry("LLM", List.of("大模型", "AI大脑", "模型统一接口", "LLM报告生成",
                    "智能教案", "自动报告")),
            Map.entry("模型服务", List.of("大模型私有化部署", "私有化大模型", "模型统一调用接口",
                    "模型并提供统一调用接口", "模型服务接口", "LLM 服务接口", "模型 API 平台")),
            Map.entry("MCP", List.of("Claude MCP")),
            Map.entry("Naive UI", List.of("NaiveUI")),
            Map.entry("Node.js", List.of("JavaScript/Node.js")),
            Map.entry("Nuxt", List.of("Nuxt 3", "Nuxt 4", "Nuxt3", "Nuxt4", "Nuxt.js")),
            Map.entry("Prompt Engineering", List.of("Prompt 设计")),
            Map.entry("Qt", List.of("Qt6")),
            Map.entry("React", List.of("React 18")),
            Map.entry("REST API", List.of("REST", "RESTful API", "REST集成")),
            Map.entry("Scikit-learn", List.of("scikit-learn")),
            Map.entry("Scrum", List.of("Agile/Scrum")),
            Map.entry("SSE", List.of("DeepSeek SSE")),
            Map.entry("Transformer", List.of("Transformer 微调")),
            Map.entry("TypeScript", List.of("JavaScript/TypeScript")),
            Map.entry("Ucos II", List.of("UcosⅡ")),
            Map.entry("UDP", List.of("UDP 多播")),
            Map.entry("UI/UX", List.of("UI/UX 优化")),
            Map.entry("Vue", List.of("Vue 1", "Vue 2", "Vue 3", "Vue2", "Vue3", "Vue.js")),
            Map.entry("Wi-Fi", List.of("WiFi")),
            Map.entry("响应式设计", List.of("CSS3响应式布局", "响应式布局")),
            Map.entry("BVA", List.of("边界值")),
            Map.entry("Regression Testing", List.of("回归测试")),
            Map.entry("探索性数据分析", List.of("探索分析")),
            Map.entry("数据集构建", List.of("数据集创建")),
            Map.entry("数据管道", List.of("data pipeline", "data pipelines", "data ingestion pipeline",
                    "data ingestion pipelines", "数据中台", "数据采集中枢", "数据资产治理", "增量同步",
                    "数据摄取", "数据摄入", "数据转换", "数据规范化")),
            Map.entry("数据清洗", List.of("数据质量处理", "字段核对", "去重处理", "缺失值整理")),
            Map.entry("数据采集", List.of("时序视频指标", "时序数据采集", "视频数据采集")),
            Map.entry("微调", List.of("模型微调", "Transformer 微调")),
            Map.entry("机器学习", List.of("AI/ML")),
            Map.entry("面向对象", List.of("OOP")),
            Map.entry("音视频", List.of("音视频推流"))
    );

    static final Map<String, List<String>> PRODUCT_CONTEXTUAL_ALIASES = Map.of(
            "原型设计", List.of("原型"),
            "交互设计", List.of("交互")
    );

    static final Map<String, List<String>> TESTING_CONTEXTUAL_ALIASES = Map.ofEntries(
            Map.entry("功能测试", List.of("功能")),
            Map.entry("Compatibility Testing", List.of("兼容性")),
            Map.entry("API Testing", List.of("接口")),
            Map.entry("Performance Testing", List.of("性能")),
            Map.entry("自动化测试", List.of("自动化")),
            Map.entry("UI Testing", List.of("UI")),
            Map.entry("App Testing", List.of("App")),
            Map.entry("Web Testing", List.of("Web")),
            Map.entry("H5 Testing", List.of("H5")),
            Map.entry("Android Testing", List.of("Android")),
            Map.entry("iOS Testing", List.of("iOS")),
            Map.entry("数据驱动测试", List.of("数据驱动")),
            Map.entry("弱网测试", List.of("弱网场景"))
    );

    static boolean isContextualAlias(String alias, String canonical) {
        return isAliasFor(PRODUCT_CONTEXTUAL_ALIASES, alias, canonical)
                || isAliasFor(TESTING_CONTEXTUAL_ALIASES, alias, canonical);
    }

    private static boolean isAliasFor(Map<String, List<String>> aliases, String alias, String canonical) {
        return aliases.getOrDefault(canonical, List.of()).stream().anyMatch(alias::equalsIgnoreCase);
    }

    private ResumeSkillVocabulary() {
    }

    static String canonicalName(String value) {
        return CANONICAL_NAMES.getOrDefault(value, value);
    }
}
