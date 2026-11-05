# 评测数据包

本目录按照当前冻结数据结构组织。`examples` 仅用于回归验证，不能作为正式 90% 指标报告。

正式评测流程：

1. 开发集、验证集、测试集分开，测试集冻结后不得用于调参。
2. 原文与标注结果分别存放，简历必须脱敏或使用人工校验的合成数据。
3. 至少两人独立标注，重大分歧由第三人或领域专家裁决。
4. JD 抽取、简历抽取和匹配分别使用不少于设计要求的样本数。
5. 通过 `/api/evaluations/extraction` 和 `/api/evaluations/classification` 计算指标。

正式报告必须包含样本数、Precision、Recall、F1、Accuracy、Macro-F1、混淆矩阵和 95% Bootstrap 区间。
