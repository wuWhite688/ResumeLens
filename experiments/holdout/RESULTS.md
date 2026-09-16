# Holdout 单配置评测

- 运行时间：2026-09-16T08:26:01.196250800Z
- 被测 commit：`86db5e1b361e57affe5047434426b9f41eb6bdee`
- 数据集：`experiments/holdout/dataset`
- holdoutVersion：`v1`
- 生产配置：chunk 900 / overlap 120 / Top-5 / minSimilarity 0.72

## 样本数

| 维度 | 分层 | 样本数 |
|---|---|---:|
| pair_type | positive | 12 |
| pair_type | negative | 12 |
| pair_type | hard_negative | 12 |
| domain_relation | seen_domain | 18 |
| domain_relation | new_domain | 18 |

## 分层结果

| 维度 | 分层 | 策略 | n | 块P | 块R | 块F1 | 证据门P | 证据门R | 证据门F1 | 门控准确率 |
|---|---|---|---:|---:|---:|---:|---:|---:|---:|---:|
| pair_type | positive | full_text | 12 | 0.500 | 1.000 | 0.667 | 1.000 | 1.000 | 1.000 | 1.000 |
| pair_type | negative | full_text | 12 | 0.000 | 0.000 | 0.000 | 0.000 | 0.000 | 0.000 | 0.000 |
| pair_type | hard_negative | full_text | 12 | 0.000 | 0.000 | 0.000 | 0.000 | 0.000 | 0.000 | 0.000 |
| domain_relation | seen_domain | full_text | 18 | 0.167 | 1.000 | 0.286 | 0.333 | 1.000 | 0.500 | 0.333 |
| domain_relation | new_domain | full_text | 18 | 0.167 | 1.000 | 0.286 | 0.333 | 1.000 | 0.500 | 0.333 |
| pair_type | positive | rag | 12 | 0.750 | 1.000 | 0.857 | 1.000 | 1.000 | 1.000 | 1.000 |
| pair_type | negative | rag | 12 | 0.000 | 0.000 | 0.000 | 0.000 | 0.000 | 0.000 | 1.000 |
| pair_type | hard_negative | rag | 12 | 0.000 | 0.000 | 0.000 | 0.000 | 0.000 | 0.000 | 0.917 |
| domain_relation | seen_domain | rag | 18 | 1.000 | 1.000 | 1.000 | 1.000 | 1.000 | 1.000 | 1.000 |
| domain_relation | new_domain | rag | 18 | 0.545 | 1.000 | 0.706 | 0.857 | 1.000 | 0.923 | 0.944 |

生产 RAG 本次触发证据门的配对数：13 / 36。

## 难负样本标注边界

按 [`dataset/README.md` 的“难负样本的词面交集与 pairFp”一节](dataset/README.md#难负样本的词面交集与-pairfp)，`H07`、`H09`、`H10` 与对应 JD 存在真实技能交集：`H07` 是 Linux/Python 日志脚本，`H09` 是流程图/原型/需求访谈，`H10` 是 Figma/用户访谈；但按 schema，它们的 `goldPhrases` 必须为空且 `shouldMatch=false`。因此这些配对一旦触发证据门，产生的 `pairFp` 属于标注期望边界，不应直接归因于检索器失灵。

本次生产 RAG 的实际逐对结果是：`H07` 过门并贡献 1 个 `pairFp`，`H09`、`H10` 未过 0.72 门槛；因此 `hard_negative` 的门控准确率 0.917（11/12）和 `new_domain` 的门控准确率 0.944（17/18）必须连同上述标注边界一起解读。

## 解读限制

完整限制见 [`PROTOCOL.md` §6](PROTOCOL.md)。尤其要注意：块级金标由人工挑定的 `goldPhrases` 按字面命中派生，会系统性偏袒关键词通路；因此块级 P/R/F1 只能在这一金标定义下解读，不能当成最终 LLM 匹配准确率。

- **领域迁移观察：** 生产 RAG 在 `new_domain` 的块精度为 0.545，明显低于 `seen_domain` 的 1.000；掉分集中在 `P08`–`P11` 各多选一块（`selected=2 / gold=1`），以及 `H07` 的一个假阳。这提示在已见领域 dev 集上确定的 0.72 阈值迁移到新领域时偏松，是 holdout 才揭示的分布迁移现象，dev 集本身无法观察。与此同时，`seen_domain` 的块 F1 1.000 不宜作为强结论：块级指标只在正样本上有定义，该格实际只有 6 个正样本。
