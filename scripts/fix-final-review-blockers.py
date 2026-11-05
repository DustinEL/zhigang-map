from pathlib import Path

from openpyxl import load_workbook


files = list(Path.cwd().glob("test-data/jd-extraction/*.xlsx"))
workbook_path = max(files, key=lambda path: path.stat().st_size)
workbook = load_workbook(workbook_path)
sheet = workbook.worksheets[1]

corrections = {
    7: [
        "参与智能制造中复杂场景（如生产运行分析、质量预测、设备故障诊断与优化控制等）的需求调研与分析，协助撰写技术方案，理解业务需求并提供数据支持及结果解释",
        "应用NLP、CV、多模态及大模型等技术，参与设计和开发智能化解决方案，负责大模型智能体与机器学习模型的研发，包括模型设计、训练、微调、评估及部署的基础工作",
        "熟悉模型预测控制（MPC）、多变量控制、自适应控制及优化控制算法研发与调试流程，运用回归、聚类、分类、时间序列分析等方法解决实际问题，提升算法准确性并优化模型性能",
        "负责编写和维护算法相关技术文档，包括原理、实现与实施细节，推动团队理解与应用，并参与知识产权申报，完成专利、论文编写及报奖材料准备",
    ],
    8: [
        "负责评测、模型服务、应用超市、AI助手和智能体开发平台研发，解决多租户、权限、任务编排、模型调用、工具接入、上线审核、运营监控等共性架构问题",
        "设计平台总体架构和技术路线",
        "制定接口、数据、权限、租户、模型接入和应用上架规范",
        "牵头高并发任务调度、多模型调用、服务治理和稳定性优化",
        "指导核心模块开发和技术评审",
    ],
    12: [
        "主导BA Agent 核心能力构建：作为核心成员，你将定义并主导包括自助取数、多维分析、归因洞察、智能可视化等关键模块的技术路径。你不仅是算法的实现者，更是产品能力的塑造者",
        "驱动前沿技术探索与落地：针对商业分析中的复杂推理、自主规划和工具使用等挑战，你将主导在多智能体协作（Multi-Agent）、强化学习（AgentRL）、代码生成（AI Coding）等方向上的探索和应用，构建技术壁垒",
        "定义并刷新业界SoTA：你将负责设计并执行严谨的算法实验，不仅要量化地超越现有解决方案，更要与团队一起，持续刷新商业分析领域的人工智能技术边界",
        "构建高效能分析系统：你将深入理解业务，与产品、业务分析师紧密合作，将前沿算法沉淀为稳定、高效的系统级解决方案，真正实现“AI赋能业务，降低决策门槛”的目标",
    ],
    32: [
        "负责开发和优化各种人工智能算法，如机器学习算法、深度学习算法等",
        "对大量数据进行分析和处理，挖掘有价值的信息和模式",
    ],
}

for sample_number, responsibilities in corrections.items():
    row = sample_number + 1
    sheet.cell(row, 16, "||".join(responsibilities))

workbook.save(workbook_path)
print(f"fixed={len(corrections)} workbook={workbook_path}")
