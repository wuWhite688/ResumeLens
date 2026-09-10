# 独立测试集（holdout）协议

本文件只定义规则，不包含数据，也不包含结果。数据与结果在后续提交中加入。

## 1. 为什么需要这一份

当前 `experiments/threshold-sweep/dataset` 的 18 组配对同时承担了两个角色：

- 用来选参数（chunk/overlap、Top-K、min similarity 的 48 组网格搜索）；
- 用来报告效果（`experiments/rag-ablation/RESULTS.md` 里 full_text vs rag 的对比数值）。

在同一批数据上选参数再报数，报出来的数值是「这批数据上的最好成绩」，不是「换一批数据还能拿到的成绩」。
本协议的目的就是把这两个角色拆开：**一批用来调，一批只在最后跑，跑完不许回头改参数**。

## 2. 集合划分

| 集合 | 目录 | 用途 | 可运行次数 |
|------|------|------|-----------|
| dev | `experiments/threshold-sweep/dataset` | 参数网格搜索、消融、调试 | 不限 |
| holdout | `experiments/holdout/dataset` | 最终报告 | 每次参数变更后至多 1 次，且必须登记 |

**按文档划分，不按配对划分。** holdout 使用的简历与 JD 文本必须是 dev 集里没有出现过的新文档。
只把 18 组配对切成两半是不够的：同一份简历出现在两边会造成泄漏——参数是在见过这份简历的分块分布上调出来的。

## 3. 组成要求

沿用 `experiments/threshold-sweep/dataset/pairs.json` 的 schema 与
`annotation-policy.md` 的标注规则（块级相关性，非整份匹配度），保持三类配对齐全：

- 正样本：简历与 JD 对口，期望至少一个金标块过阈；
- 负样本：跨领域，期望零块过阈；
- 难负样本：同大领域不同方向（如后端 × 前端、算法 × 数据分析），期望零块过阈。

难负样本是这套评测里唯一有区分度的部分，占比不应低于三分之一。
样本领域不要与 dev 集完全重合，否则只是换了名字的同一批数据。

## 4. 冻结纪律

- 参数（阈值、Top-K、chunk 尺寸）一律在 dev 集上选定，写进配置后再碰 holdout。
- 每次 holdout 运行在 `RUN-LOG.md` 追加一行：日期、被测 commit、参数取值、结果文件路径。
- 如果看完 holdout 结果又去改参数，改完的那一版**不能**再用同一份 holdout 报数，需要在 `RUN-LOG.md` 里如实记为第 N 次运行。多次运行后 holdout 会逐渐退化成第二个 dev 集，这一点必须写在结果里。

## 5. 运行方式（待补）

`experiments/rag-ablation/run-ablation.ps1` 通过 `THRESHOLD_SWEEP_DIR` 环境变量定位数据集，
因此换数据集本身不需要改脚本。但现有测试入口
`ThresholdSweepExperimentTests#compareFullTextWithRagAcrossConfigurations`
跑的是 48 组参数网格——直接拿它跑 holdout 等于在 holdout 上又搜了一遍参数，与本协议第 4 条冲突。

因此 holdout 的运行入口需要一个「单配置」模式：只用当前生产配置跑一遍，输出一份报告。
该改动属于实验代码，另行提交，不在本 PR 范围内。

## 6. 已知局限（对外须如实说明）

- 简历为自建构造样本，非真实投递简历；JD 为真实招聘页抓取后脱敏。
- 标注人为项目作者本人，单人标注，无第二标注者，因此没有标注一致性（IAA）指标。
- 样本规模为两位数量级，指标的置信区间很宽，只能支持「RAG 证据筛选优于全文直喂」这一量级判断，不足以支持精确数值比较。
- 评测对象是「哪些分块应作为证据进入 prompt」，不是 LLM 最终给出的匹配分数准确率。
