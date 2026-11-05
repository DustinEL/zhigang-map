from __future__ import annotations

from pathlib import Path

from docx import Document
from docx.enum.section import WD_SECTION
from docx.enum.text import WD_ALIGN_PARAGRAPH
from docx.oxml import OxmlElement
from docx.oxml.ns import qn
from docx.shared import Cm, Pt, RGBColor


ROOT = Path(__file__).resolve().parents[1]
OUTPUT = ROOT / "测试简历_全新5份_中文"


RESUMES = [
    {
        "id": "01",
        "folder": "01_DetimZhao_Java后端与系统工程",
        "name": "Detim Zhao",
        "category": "Java / 后端与系统工程",
        "contact": "电话：480-207-9033  |  邮箱：detimzhao@gmail.com  |  LinkedIn：linkedin.com/in/detimzhao  |  GitHub：github.com/DetimZhao",
        "summary": "软件工程师，具备 Java、Python、C/C++、Go、C#、SQL 与后端分布式系统实践，覆盖微服务、数据管道、高性能计算基础设施、云部署和 AI 数据应用。",
        "source": ".resume_candidate26/extracted/DetimZhao_resume/resume-main",
        "sections": [
            ("技术技能", [
                ("编程语言", "Java、Python、C/C++、Go、C#、SQL（PostgreSQL/MySQL）、Bash/Shell", []),
                ("框架与数据", "PyTorch、scikit-learn、pandas、NumPy、OpenCV、Flask、FastAPI、Protocol Buffers、ChromaDB、RAG、ETL", []),
                ("系统与云平台", "Kafka、Telegraf、InfluxDB、Grafana、Redfish、systemd、GitLab CI/CD、Docker/Podman、Ansible、AWS", []),
                ("工程工具", "Pytest、JUnit、Git、LaTeX、Agile、Jira", []),
            ]),
            ("工作经历", [
                ("洛斯阿拉莫斯国家实验室", "高性能计算（HPC）工程实习生 | Los Alamos, NM", [
                    "2026.05 至今",
                    "使用 Redfish API、Kafka、InfluxDB 与 Telegraf 设计并部署硬件遥测管道，通过容器化微服务和 systemd 自动化每日采集 83 万余条数据，保持零数据丢失。",
                    "逆向分析 Intel Redfish API，为 6 类端点实现遥测轮询与事件订阅，并以 Python Flask 接收器完成 Kafka 与文件存储双写。",
                    "使用 OpenCHAMI 配置多节点 Rocky Linux 集群，以 Kickstart 和 cloud-init 自动部署操作系统，并建立 GitLab CI/CD，在提交后自动构建和测试启动镜像。",
                ]),
                ("Mesh Labs（原 Meteor Studio）", "扩展现实（XR）开发工程师 | Mesa, AZ", [
                    "2024.07 - 2026.05",
                    "使用 Docker Compose 容器化支持 GPU 的多依赖环境，将配置时间从 60 分钟缩短至 16 分钟，并消除 Linux/WSL2 兼容问题。",
                    "开发 Python 3D 相机标定管道，使用基于 RANSAC 的特征匹配与 ICP 点云配准实现精确多视角场景重建。",
                ]),
                ("Texas A&M University-Corpus Christi", "本科研究员 | Corpus Christi, TX", [
                    "2023.06 - 2023.08",
                    "在 Gazebo 仿真中开发 PX4 多无人机控制系统，调试软硬件边界的时序与通信问题，可协调最多 10 架自主无人机。",
                    "设计基于窗口的时序传感器数据预处理管道，训练并验证 KNN、SVM、随机森林与朴素贝叶斯集成模型，使手势识别准确率提高 10%。",
                ]),
            ]),
            ("项目经历", [
                ("gRPC 微服务系统", "Protocol Buffers / AWS EC2", [
                    "设计包含 5 个 gRPC 微服务的分布式系统，提供交互式 CLI 与自动客户端接口，并实现健壮的错误处理和模块化服务设计。",
                    "实现 Fitness、RecipeBook、Sort、Joke、Echo 服务，支持文件持久化、服务注册发现与线程安全并发请求。",
                    "部署至 AWS EC2 并开放公网访问，验证多客户端并发服务请求。",
                ]),
                ("AI 集成无线紧急警报", "FastAPI / ChromaDB / RAG", [
                    "构建集成 RAG 与 ChromaDB 的 FastAPI 微服务，实现语义检索和上下文感知的 LLM 响应。",
                    "设计容器化自动化系统，通过自定义调度和健康端点编排数据摄取、抓取与 LLM 管道。",
                    "开发具备指数退避机制的 PBS WARN API 抓取器，并实现每两小时批量清理过期警报的 ChromaDB 服务。",
                ]),
                ("Semantic Arithmetic Playground", "Three.js / Python / PCA", [
                    "开发交互式 3D 向量运算探索器，支持公式解析、Levenshtein 自动纠错和 384 维向量运算。",
                    "构建 Python 管道，将来自 HuggingFace、arXiv、Wikipedia 与 PyTorch 文档的 1,294 个 AI/ML 概念通过 PCA 转换为 3D 嵌入。",
                ]),
            ]),
            ("教育背景", [("Arizona State University, Polytechnic", "软件工程理学学士 | Mesa, AZ", ["2026.05"])]),
        ],
    },
    {
        "id": "02", "folder": "02_AkashRathore_前端与全栈开发", "name": "Akash Rathore",
        "category": "前端 / 全栈开发",
        "contact": "所在地：Noida, Uttar Pradesh  |  电话：+91-8423418749  |  邮箱：akashrathore8118@gmail.com  |  GitHub：github.com/akashcs21",
        "summary": "前端与全栈开发工程师，专注 React、Next.js、Redux Toolkit、Node.js 与 Express.js，具备企业级 DCIM 平台、可视化仪表板和 MERN 全栈应用经验。",
        "source": ".resume_candidate26/extracted/akashcs21_AkashResume/AkashResume-main/overleafResume.tex",
        "sections": [
            ("工作经历", [
                ("NuageBizTech", "软件开发工程师 | Noida, Uttar Pradesh", [
                    "2026.02 至今",
                    "使用 React.js、Redux Toolkit 与 Material UI 开发维护企业级数据中心基础设施管理（DCIM）平台核心模块。",
                    "负责活动告警、异常设备与资源利用率模块，使基础设施团队能够监控设备健康和运行状态。",
                    "使用 Axios 集成 REST API，并以 Redux Toolkit 异步流程管理告警、环境指标、资源利用率及网络监控数据。",
                    "使用 ECharts 构建仪表、堆叠图、柱状图及利用率指标等交互式数据可视化仪表板。",
                    "设计表格、表单、对话框和选择器等可复用组件；通过响应式设计、分页、列表虚拟化、表单校验与国际化改善性能和可用性，并与 QA 协作解决缺陷。",
                ]),
                ("NuageBizTech", "软件开发实习生 | Noida, Uttar Pradesh", [
                    "2025.07 - 2026.01",
                    "为设备详情与站点管理模块开发可复用 React.js 组件，参与 DCIM 平台前端建设。",
                    "集成 REST API，并以 Redux 管理仪表板状态；与开发和 QA 团队协作完成开发与测试。",
                ]),
            ]),
            ("项目经历", [
                ("LifeRelay 全栈 Web 应用", "React.js / Node.js / Express.js / MongoDB | 2025", [
                    "使用 MERN 技术栈开发全栈应用，构建响应式 React 前端和 Node.js、Express.js REST 后端服务。",
                    "实现可复用 React 组件、REST API 集成及 MongoDB 数据功能，采用模块化架构提升可维护性和扩展性。",
                ]),
                ("外卖全栈应用", "React.js / Node.js / Express.js / MongoDB | 2024", [
                    "构建响应式 React 前端并集成 REST 后端 API。",
                    "设计可复用 UI 组件，整合 Express.js、Node.js 与 MongoDB 数据功能，改善应用维护性和用户体验。",
                ]),
            ]),
            ("技术技能", [
                ("语言与前端", "JavaScript（ES6+）、TypeScript、Java、SQL；React.js、Next.js、HTML5、CSS3、Redux Toolkit、Material UI、Tailwind CSS、React Router、ECharts、i18next", []),
                ("后端与数据", "Node.js、Express.js、REST API、Axios、Fetch、Postman；MongoDB、PostgreSQL、Redis", []),
                ("DevOps 与工具", "Docker、Podman、Kubernetes、Kind、CI/CD、Nginx、Kafka、Keycloak、Git、GitLab、Vite、npm、VS Code", []),
            ]),
            ("教育背景", [("GLA University, Mathura", "计算机科学与工程技术学士（B.Tech） | CGPA 7.91", ["2021 - 2025"])]),
            ("认证", [
                ("Udemy", "React - The Complete Guide（含 Next.js、Redux）", []),
                ("Udemy", "The Complete JavaScript Course 2025: From Zero to Expert!", []),
            ]),
        ],
    },
    {
        "id": "03", "folder": "03_KennethLiu_AI与数据应用", "name": "Kenneth Liu",
        "category": "AI / 数据应用",
        "contact": "邮箱：liu.h.kenneth@gmail.com  |  LinkedIn：linkedin.com/in/kennethhliu  |  GitHub：github.com/kennethliu0  |  主页：kennethliu.me",
        "summary": "计算机科学本科生，具备 AI 原生产品、多智能体研究、后端系统和全栈开发经历，熟悉 Python、FastAPI、LangGraph、LangChain、React、Express.js 与数据存储。",
        "source": ".resume_new13_search/extracted/kennethliu0__resume/resume-main/Kenneth_Liu_resume.tex",
        "sections": [
            ("工作经历", [
                ("Stripe", "软件工程实习生 | Seattle, WA", ["2026.06 - 2026.09", "构建 Ruby 代码生成步骤，为新的支付方式自动完成 Radar 欺诈防护集成。", "设计并实现 Slack Agent，对运行查询进行分流与回答，自动处理 35% 的问题。"]),
                ("Stanford Carta", "后端软件工程师 | Stanford, CA", ["2026.01 至今", "将斯坦福课程规划平台 Carta 从 ASP.NET 6 升级至 ASP.NET 8，修复 10 个安全问题。", "实现客户端模糊搜索，将课程搜索延迟降低 90%，支持实时预览。"]),
                ("LastingLearn, Inc.", "软件工程实习生 | Stanford, CA", ["2025.10 - 2026.01", "使用 React 与 Express.js 设计 AI 原生 SAT 备考平台，集成 LLM，根据学生历史表现生成个性化练习题。", "使用 Redis Sorted Sets 构建排行榜，与标准 SQL 聚合相比查询延迟降低 90%，可处理 500 余次并发更新。"]),
                ("Baker Engineering and Risk Consultants, Inc.", "软件工程实习生 | Houston, TX", ["2024.06 - 2024.08", "使用 C#/.NET WPF 开发支持动态树编辑的事件树分析软件，使分析时间减少 80%。"]),
            ]),
            ("项目经历", [
                ("CiteSite", "Python / FastAPI / LangGraph", ["在耶鲁大学 YHack 2026 年度黑客松获得 500 美元奖金。", "整合学术研究数据库，核验论文引用来源与陈述主张的相关性。"]),
                ("BobaFinder", "Python / LangGraph / LangChain / MongoDB Atlas", ["构建多智能体市场进入研究系统，用于判断珍珠奶茶连锁门店的最佳选址。", "使用 LangGraph Swarm 支持多智能体协作。"]),
                ("Decanter", "JavaScript / React / TypeScript / Next.js / Node.js / Supabase / PostgreSQL", ["为科学奥林匹克竞赛构建志愿者管理平台，使用 Next.js、Supabase 和 Google OAuth，上线首月吸引 75 余名用户。", "使用 Vitest 自动化测试服务端函数，代码覆盖率达到 97%。"]),
            ]),
            ("技术技能", [
                ("语言", "C、C#、C++、CSS、HTML、Java、JavaScript/TypeScript、Python", []),
                ("框架与工具", "FastAPI、LangGraph、LangChain、Express.js、React、.NET、Next.js、Node.js、Azure DevOps、Docker、Git、Linux、VS Code、Visual Studio", []),
                ("数据库", "SQL、MongoDB、PostgreSQL、Redis、Supabase", []),
            ]),
            ("教育背景", [("Stanford University", "计算机科学理学学士 | GPA 4.09/4.0 | 预计 2028.06", ["课程：操作系统原理、计算机组成与系统、编程抽象、计算数学基础、概率论、线性代数与多元微积分、前沿系统。"])]),
            ("荣誉", [("American Invitational Mathematics Examination", "三次获得 AIME 参赛资格", [])]),
        ],
    },
    {
        "id": "04", "folder": "04_ArsanySamuel_DevOps与云平台", "name": "Arsany Samuel",
        "category": "测试 / DevOps 与云平台",
        "contact": "电话：+20 106 887 4670  |  邮箱：arsanysamuel.as@gmail.com  |  LinkedIn：linkedin.com/in/arsanysamuel  |  GitHub：github.com/arsanysamuel",
        "summary": "DevOps 与后端工程师，熟悉 GCP、AWS、Terraform、Docker、Kubernetes、Helm、CI/CD、可观测性和 Python Web 框架，具备基础设施、ERP、数据管道及硬件集成经验。",
        "source": ".resume_new13_search/extracted/arsanysamuel__resume/resume-main/Arsany_Samuel.tex",
        "sections": [
            ("工作经历", [
                ("Noury AG", "DevOps 工程师 / 软件开发与 DevOps 工程师 | 瑞士远程", [
                    "2023.03 - 2026.06",
                    "使用 Terraform 在 GCP 设计并管理安全云基础设施，优化资源与安全实践，使月度云成本降低 50%-60%。",
                    "使用 Docker 容器化应用，通过 Kubernetes 与 Helm 编排高可用部署；以 Bitbucket Pipelines 构建多个项目的 CI/CD。",
                    "建立监控与可观测性体系，改善事件响应；优化版本控制与本地环境流程，提高部署频率。",
                    "使用 Django REST Framework 为 Noury 消费者移动应用开发可扩展 REST API。",
                    "使用 Frappe/ERPNext 构建定制 ERP，整合第三方数据源和 API；交付数据管道、自动化分析和 AI 集成，并指导初级工程师和实习生。",
                ]),
                ("YourStore Systems AG", "DevOps 工程师 | 瑞士合同制", [
                    "2024.11 - 2025.05",
                    "领导后端团队使用 Python、Frappe 与 ERPNext 设计无人零售平台 API 和模块化 ERP。",
                    "以 Terraform 构建 GCP 基础设施，使用 Docker、Kubernetes 与 Helm 编排，并通过 GitHub Actions 建立 CI/CD。",
                    "在开发前 6 个月内上线 3 个客户无人商店项目。",
                ]),
                ("Specialist Company for Engineering Solutions", "软件与机电一体化工程师 | Alexandria, Egypt", [
                    "2021.06 - 2022.03",
                    "为自动仓储机器人开发桌面应用，结合机器视觉完成产品拣选与分拣，并确保系统数据一致性。",
                    "为楼宇管理系统设计、实现和测试定制原型，覆盖机械、电气与 PCB 布局并增加安全功能。",
                ]),
                ("自由职业", "软件开发工程师", ["2019.11 - 2023.02", "提供软硬件结合的原型与软件方案，涉及图像处理、桌面应用框架和 SQL 数据库。"]),
            ]),
            ("技术技能", [
                ("软件与框架", "Python、Bash/Shell、JavaScript、SQL、HTML/CSS、Markdown、LaTeX；Django、DRF、FastAPI、Flask、Frappe/ERPNext", []),
                ("DevOps 与云", "Linux、Git、Docker、Kubernetes、Terraform、Helm、GitHub Actions、Bitbucket Pipelines、GCP、AWS", []),
            ]),
            ("项目经历", [
                ("Noury Consumer App", "Django REST Framework / Docker / Kubernetes", ["开发可扩展 REST API；优化 CI/CD 与部署流程，并实施监控日志策略以保障 API 性能和可靠性。"]),
                ("Noury Resource Planning", "Frappe / ERPNext", ["构建定制 ERP，整合多个第三方数据源和 API，实现实时同步、自动报告与流程自动化，并针对业务增长优化性能和扩展性。"]),
                ("YourStore Platform", "Python / GCP / Terraform / Kubernetes", ["领导后端 API 和 ERP 交付，构建 IaC、CI/CD、容器化与编排配置，并在前 6 个月上线 3 个客户项目。"]),
            ]),
            ("培训与认证", [
                ("AWS", "Solutions Architect Associate | 2026.04", []),
                ("Google Cloud", "Professional Cloud Architect | 2026.02", []),
                ("Google Cloud", "Associate Cloud Engineer | 2025.06", []),
                ("IBM", "Agile Explorer | 2023.04", []),
                ("EPFL Extension School", "Thinking and Creating with Code | 2023.03", []),
                ("Harvard University", "CS50x | 2022.09", []),
                ("Dassault Systèmes", "CSWE: Certified SolidWorks Expert - Mechanical Design | 2021.07", []),
            ]),
            ("教育背景", [("Alexandria University", "农业工程工学学士 | Faculty of Agriculture", ["2016 - 2021"])]),
        ],
    },
    {
        "id": "05", "folder": "05_YounghwanChae_嵌入式感知与移动机器人", "name": "Younghwan Chae",
        "category": "嵌入式 / 移动机器人与多传感器感知",
        "contact": "邮箱：chyohw97@gmail.com  |  LinkedIn：linkedin.com/in/younghwan-chae  |  GitHub：github.com/younghwan91",
        "summary": "博士背景机器学习工程师，覆盖自动驾驶与机器人、相机/雷达/LiDAR 多传感器感知、3D 视觉、TensorRT INT8 嵌入式部署和 MLOps；拥有 10 项专利与 6 篇同行评议论文，成果量产并部署至 8 个国家 200 余处。",
        "source": ".resume_new13_search/extracted/younghwan91__resume/resume-main/en/resume.tex",
        "sections": [
            ("工作经历", [
                ("Doosan Robotics", "机器学习与感知工程师，Physical AI Platform Team", [
                    "2025.08 至今",
                    "构建工业拆垛视觉管道（RF-DETR 检测 + SAM 分割头），mAP@0.5 达 94.2%，端到端约 150ms，使周期缩短 47%、错误率降低 85%。",
                    "从原始 3D 扫描自动生成机器人打磨覆盖路径，以 RBF 曲面重建和曲率自适应优化将 2-4 小时人工流程缩短至 5-15 秒，并集成为 Agentic Robot OS 技能。",
                    "建立 DVC、Kubeflow、BentoML、Prometheus/Grafana MLOps 栈与 KPI 体系。",
                    "构建 LLM + 知识图谱系统和单目 RGB 模仿学习管道，完成 OWL/RDF、本体与混合检索、VLA 数据和 LoRA 策略微调。",
                    "获评 AI&SW Division 2025 年度优秀员工，并参与 Stanford REAL Lab 联合研究提案。",
                ]),
                ("bitsensing Inc.", "AI 感知工程师，信号处理团队", [
                    "2023.09 - 2025.08",
                    "开发 3D 雷达感知系统，包括 PV-RCNN 点云检测、DGCNN 点特征学习、CenterPoint-BEV/AB3DMOT 3D 多目标跟踪；深度学习超分辨率 DoA 估计使角分辨率提升 2 倍。",
                    "构建信号去噪、偏差校正与合成数据 ML 管道，通过知识蒸馏、剪枝和 TensorRT INT8 量化完成实时嵌入式部署且无准确率回退。",
                    "提交 7 项 AI 信号处理、不确定性量化与嵌入式学习专利。",
                ]),
                ("bitsensing Inc.", "传感器融合工程师，跟踪与应用团队", [
                    "2022.08 - 2023.09",
                    "构建相机/雷达融合感知系统：YOLO 8 类障碍物检测、SORT/IMM/Kalman 多目标跟踪（12 车道最多 256 目标）、PP-LiteSeg/U-Net 车道分割与 ML 误报剔除。",
                    "通过非线性重投影优化、相机标定与多视图几何使融合 RMSE 降低 51%；成果量产，在 8 个国家部署 200 余套，并以 TensorRT 完成边缘优化。",
                    "开发并产品化交通雷达自动标定电机控制系统；提交 3 项传感器融合和概率目标跟踪专利。",
                ]),
                ("University of Pretoria", "研究生研究员与助理讲师 | Pretoria, South Africa", [
                    "2015.01 - 2021.12",
                    "博士研究提出 GOALS 梯度线搜索近似，改善非凸噪声损失下训练稳定性与收敛，发表于 Springer Nature《Applied Intelligence》。",
                    "研究高维 ML 优化的子维代理建模和数据驱动传感器最优布置，成果发表于 IFSA 与 Elsevier。",
                    "为本科生讲授数学优化与数值分析；学士、荣誉学士和硕士均以 Cum Laude 毕业。",
                ]),
            ]),
            ("技术技能", [
                ("语言与平台", "Python、C/C++、MATLAB、Git、Docker、Linux/Unix、Jupyter、LaTeX、VS Code、MySQL", []),
                ("机器学习与科学计算", "PyTorch、Transformers、DETR/RF-DETR、YOLO、PV-RCNN、CenterPoint、DGCNN、SAM、LoRA/PEFT、TensorRT、TensorFlow、OpenCV、CUDA、ONNX、MuJoCo、NumPy、SciPy、scikit-learn", []),
                ("传感器与领域", "FMCW 4D 成像雷达、RGB/PTZ/RealSense/Zivid 相机、LiDAR、IMU、GPS；传感器融合、3D 感知、点云、视觉 SLAM、语义分割、概率建模、强化学习", []),
            ]),
            ("教育背景", [
                ("University of Pretoria", "机械工程博士", ["2017 - 2021"]),
                ("University of Pretoria", "机械工程硕士（Cum Laude）", ["2016"]),
                ("University of Pretoria", "机械工程荣誉学士（Cum Laude）", ["2015"]),
                ("University of Pretoria", "机械工程学士（Cum Laude）", ["2011 - 2014"]),
            ]),
            ("论文与预印本", [
                ("同行评议论文", "6 篇", ["IEEE Sensors 2025：雷达与视觉传感器基于轨迹集成的精度改进（共同第一作者）。", "IEEE Sensors 2024：毫米波雷达深度学习踢腿动作识别。", "IEEE RadarConf 2024：60 GHz 雷达踢腿手势识别。", "Applied Intelligence 2023：用于神经网络训练的梯度代理线搜索。", "Advances in AI Reviews 2019：高维优化的子维代理模型。", "Engineering Structures 2017：高效传感器布置的秩-方差方法。"]),
                ("预印本", "2 篇", ["arXiv:2105.10915 GOALS（后发表于 Applied Intelligence, 2023）。", "arXiv:1909.06893 神经网络训练线搜索近似实证研究。"]),
            ]),
            ("专利", [("国内及国际专利", "共提交 10 项（含美国、欧洲）", ["覆盖传感器融合、环境感知、传感器数据结构化与质量增强、训练数据与模型鲁棒性、恶劣条件感知、目标定位跟踪和不确定性量化。"])]),
            ("奖项与荣誉", [
                ("University of Pretoria", "MEng Academic Honorary Colours | 2017", []),
                ("University of Pretoria", "BEng Honours Merit Certificate | 2016", []),
                ("University of Pretoria", "BEng Achievement Award & Academic Honorary Colours | 2015", []),
                ("Golden Key International Honour Society", "会员 | 2011", []),
                ("South African National Mathematics Olympiad", "全国前 100、州级第 2 名 | 2010", []),
            ]),
        ],
    },
]


def set_cell_shading(cell, fill: str) -> None:
    tc_pr = cell._tc.get_or_add_tcPr()
    shd = OxmlElement("w:shd")
    shd.set(qn("w:fill"), fill)
    tc_pr.append(shd)


def set_font(run, size: float, bold: bool = False, color: str = "222222") -> None:
    run.font.name = "Microsoft YaHei"
    run._element.get_or_add_rPr().rFonts.set(qn("w:eastAsia"), "Microsoft YaHei")
    run.font.size = Pt(size)
    run.bold = bold
    run.font.color.rgb = RGBColor.from_string(color)


def configure(document: Document) -> None:
    section = document.sections[0]
    section.top_margin = Cm(1.45)
    section.bottom_margin = Cm(1.4)
    section.left_margin = Cm(1.75)
    section.right_margin = Cm(1.75)
    styles = document.styles
    normal = styles["Normal"]
    normal.font.name = "Microsoft YaHei"
    normal._element.rPr.rFonts.set(qn("w:eastAsia"), "Microsoft YaHei")
    normal.font.size = Pt(9.5)
    normal.paragraph_format.space_after = Pt(2.5)
    normal.paragraph_format.line_spacing = 1.06
    for name in ("Title", "Heading 1", "Heading 2"):
        style = styles[name]
        style.font.name = "Microsoft YaHei"
        style._element.rPr.rFonts.set(qn("w:eastAsia"), "Microsoft YaHei")
    styles["Heading 1"].font.size = Pt(13)
    styles["Heading 1"].font.bold = True
    styles["Heading 1"].font.color.rgb = RGBColor(31, 78, 95)
    styles["Heading 1"].paragraph_format.space_before = Pt(7)
    styles["Heading 1"].paragraph_format.space_after = Pt(3)


def add_entry(document: Document, title: str, subtitle: str, bullets: list[str]) -> None:
    table = document.add_table(rows=1, cols=2)
    table.autofit = False
    table.columns[0].width = Cm(11.8)
    table.columns[1].width = Cm(6.0)
    left = table.cell(0, 0)
    right = table.cell(0, 1)
    left.text = ""
    right.text = ""
    left_p = left.paragraphs[0]
    set_font(left_p.add_run(title), 10, True, "1F4E5F")
    right_p = right.paragraphs[0]
    right_p.alignment = WD_ALIGN_PARAGRAPH.RIGHT
    set_font(right_p.add_run(subtitle), 8.7, False, "555555")
    for cell in (left, right):
        cell.vertical_alignment = 1
        for p in cell.paragraphs:
            p.paragraph_format.space_after = Pt(0)
    for bullet in bullets:
        p = document.add_paragraph(style="List Bullet")
        p.paragraph_format.left_indent = Cm(0.45)
        p.paragraph_format.first_line_indent = Cm(-0.2)
        set_font(p.add_run(bullet), 9.2)


def build_resume(item: dict) -> Path:
    folder = OUTPUT / item["folder"]
    folder.mkdir(parents=True, exist_ok=True)
    document = Document()
    configure(document)

    banner = document.add_table(rows=1, cols=1)
    cell = banner.cell(0, 0)
    set_cell_shading(cell, "1F4E5F")
    cell.text = ""
    name_p = cell.paragraphs[0]
    name_p.alignment = WD_ALIGN_PARAGRAPH.CENTER
    set_font(name_p.add_run(item["name"]), 22, True, "FFFFFF")
    cat_p = cell.add_paragraph()
    cat_p.alignment = WD_ALIGN_PARAGRAPH.CENTER
    set_font(cat_p.add_run(item["category"]), 11, True, "DDEBF0")

    contact = document.add_paragraph()
    contact.alignment = WD_ALIGN_PARAGRAPH.CENTER
    contact.paragraph_format.space_before = Pt(4)
    set_font(contact.add_run(item["contact"]), 8.2, False, "555555")

    document.add_heading("个人概要", level=1)
    p = document.add_paragraph()
    set_font(p.add_run(item["summary"]), 9.5)

    for heading, entries in item["sections"]:
        document.add_heading(heading, level=1)
        for title, subtitle, bullets in entries:
            add_entry(document, title, subtitle, bullets)

    document.add_heading("来源说明", level=1)
    p = document.add_paragraph(style="List Bullet")
    set_font(p.add_run(f"原始公开资料：{item['source']}"), 8.8)
    p = document.add_paragraph(style="List Bullet")
    set_font(p.add_run("本文件仅对原始公开简历的有效内容进行中文翻译与统一排版；未补写个人经历，技术名词、时间和量化结果以原资料为准。"), 8.8)

    document.core_properties.title = f"{item['name']} - {item['category']}（中文完整简历）"
    document.core_properties.subject = "全新盲测候选中文简历"
    document.core_properties.comments = f"Source: {item['source']}"
    target = folder / f"{item['id']}_{item['name'].replace(' ', '')}_{item['category'].replace(' / ', '_')}_中文完整简历.docx"
    document.save(target)
    return target


def convert_to_pdf(paths: list[Path]) -> list[Path]:
    import win32com.client

    word = win32com.client.DispatchEx("Word.Application")
    word.Visible = False
    pdfs = []
    try:
        for path in paths:
            pdf = path.with_suffix(".pdf")
            doc = word.Documents.Open(str(path.resolve()), ReadOnly=True)
            try:
                doc.ExportAsFixedFormat(str(pdf.resolve()), 17)
            finally:
                doc.Close(False)
            pdfs.append(pdf)
    finally:
        word.Quit()
    return pdfs


def write_audit() -> None:
    lines = [
        "# 五份中文简历来源与未使用核对说明",
        "",
        "这五名候选人在生成前已对 `test-data` 下的 JSON、JSONL、CSV 与 Markdown 评测记录执行姓名全文检索，命中数均为 0。",
        "",
        "| 方向 | 候选人 | 评测记录命中数 | 原始资料 |",
        "|---|---|---:|---|",
    ]
    for item in RESUMES:
        lines.append(f"| {item['category']} | {item['name']} | 0 | `{item['source']}` |")
    lines.extend([
        "",
        "## 制作原则",
        "",
        "- 中文版本保留原始资料中的有效经历、项目、技能、教育、认证、论文、专利和奖项。",
        "- 原始文件未被移动、修改或删除。",
        "- 未引入原资料之外的个人经历或量化成果。",
    ])
    (OUTPUT / "来源与未使用核对说明.md").write_text("\n".join(lines) + "\n", encoding="utf-8")


def main() -> None:
    OUTPUT.mkdir(parents=True, exist_ok=True)
    paths = [build_resume(item) for item in RESUMES]
    write_audit()
    pdfs = convert_to_pdf(paths)
    for path in paths + pdfs:
        print(path)


if __name__ == "__main__":
    main()
