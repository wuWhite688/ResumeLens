# Holdout v1 dataset

状态：**待通过 PR 冻结，尚未执行正式 holdout run。**

## 组成

- 12 份全新合成简历。
- 12 份来自公开招聘页、经脱敏压缩改写的 JD。
- 36 个配对：`positive=12`、`negative=12`、`hard_negative=12`。
- 领域关系：`seen_domain=18`、`new_domain=18`。
- 正样本的 `goldPhrases` 数量按实际证据选择，不要求固定个数；负样本与难负样本固定为空。
- seen/new 两组的普通负样本都把目标 JD 摊到 6 个岗位上，避免某一份 JD 过度主导负样本结果。

## goldPhrases 的三类诊断标签

`goldPhrases` 仍然只用来派生“哪些简历块是相关块”的定位锚点，所有短语都必须在对应简历原文中逐字出现。`goldPhraseKinds` 不改变金标生成，只用于解释一条 gold 证据和当前生产 lexical 路径的关系：

- `lexical`：gold phrase 本身在对应 JD 中逐字出现。
- `mixed`：gold phrase 不在 JD 中逐字出现，但把该 phrase 当作证据文本实际送入当前生产 lexical 路径时，仍可仅靠词面关系命中。当前合同测试会直接复用 `ResumeRagService` 的 JD 关键词抽取、substring keyword match，以及 hard-skill alias 边界匹配来判断，而不是靠人工肉眼猜。例如 `MyBatis-Plus` 虽不逐字出现在 JD 中，但 JD 的 `MyBatis` 关键词可以命中它，因此属于 `mixed`。
- `semantic`：gold phrase 不在 JD 中逐字出现，并且上述当前生产 lexical 路径也无法仅靠该 phrase 的词面内容取得命中；其事实含义由人工标注为能支撑目标 JD。

三类标签由 `HoldoutDatasetContractTests` 确定性核验。这样做的原因是，短语级“看起来不一样”不等于生产 lexical 路径真的拿不到：substring、ASCII/CJK 边界和 hard-skill alias 都可能制造肉眼容易漏掉的词面命中。之前提出的“生产分词 token 集合与 JD token 集合零交集才算 semantic”判据已放弃，因为当前生产 lexical 通路并不是 analyzer + BM25，拿另一套分词假设做判据并不贴实现。

这个分类仍然只是**诊断标签，不是新的金标生成规则**：块只要命中任一 `goldPhrase` 就算 gold relevant。即使某个 `semantic` phrase 所在块被召回，也不能直接声称“语义检索胜过 BM25/关键词检索”，因为同一个 900 字块仍可能同时包含其他 lexical 或 mixed 证据。

## 与分块配置的关系

v1 在当前生产配置 `chunkSize=900`、`overlap=120` 下做了确定性静态检查：每份简历至少会被切成 2 个文本块，且每个正样本至少有 1 个 gold-relevant chunk。这项检查只运行 `TextChunker`，不读取 embedding、similarity、Top-K 或 gate 输出。

**这意味着 v1 的诊断价值依赖当前分块尺度。** 协议里“gold 会随 chunk 配置重新派生”仍然成立，但如果未来生产配置改大（例如 1200/160）导致部分简历退化成单块，v1 的块级指标会失去区分度。遇到这种情况不要回头拉长或改写 v1 文档；v1 已冻结，应新建新的 holdout 版本。

`HoldoutDatasetContractTests` 会在普通测试中检查上述前提。如果未来生产分块配置使 v1 不再满足至少 2 块，测试会显式失败，提醒创建新版本，而不是静默继续报块级指标。

## 冻结前结构修正边界

在第一次正式 holdout 运行前，合同测试发现 `analyst-he` 在生产 `900/120` 配置下只会形成 1 个 chunk。随后只根据这个**确定性的结构结果**补充了明确不作为目标证据的背景文本，使其达到至少 2 块。该修正发生时没有查看 embedding、similarity、Top-K、gate 或任何检索输出，因此属于运行前的数据结构校验，不属于根据模型结果反调 holdout 数据。

同样在正式运行前，`P10` 的一条 gold phrase 由 `埋点` 改为 `上线前为关键操作定义埋点事件`。原短语只有两个字，在 `product-manager-xu` 中除「客户运营模块」的真实证据外，还会被简历末尾「工作习惯」段的否定句「不会用没有埋点支持的数据声称功能提升」命中；该段周围是英语阅读、业余兴趣一类明确不作为目标证据的内容。替换后的短语只落在「客户运营模块」段。

这项修正同样只依据确定性检查：短语在简历原文中逐字可定位，且分类由 `HoldoutDatasetContractTests` 按当前生产 lexical 路径核验。核验结果为 `verbatimInJd=false`、`productionLexicalHit=false`，因此该短语归入 `semantic` 而非原 `埋点` 所在的 `lexical`——`埋点` 曾逐字出现在目标 JD 的「上线后结合埋点」中，替换后的长短语不再具备这一词面关系。`P10` 的 `lexical` 因此由 6 条减为 5 条，仍满足合同测试对每个正样本至少保留一条 lexical 与一条 semantic 的要求。

需要说明的是，在当前生产 `900/120` 配置下这项替换不改变金标块集合：`product-manager-xu` 只切出 2 块，`chunk#0`（893 字）同时包含「客户运营模块」与「工作习惯」两段，替换前后金标块都只有 `chunk#0`。该修正的价值在于对分块尺度的鲁棒性——一旦 chunk 尺寸调小到「工作习惯」单独成块，原 `埋点` 会把该噪声块拉成金标块，替换后的短语不会。这与协议中「冻结单位是文档 + goldPhrases，而非某一份已生成的分块标注文件」一致。

`RUN-LOG.md` 的 v1 仍保持“尚未运行”。上述结构修正、gold kind 重标与 `P10` 的 gold phrase 替换都发生在第一次正式报数之前，不消耗一次性 holdout 机会。

## 标注约束

gold 的问题是“哪些简历块含有能直接支撑目标 JD 的事实”，而不是“候选人整体像不像岗位”。因此教育背景、通用协作、自我评价、社团与课程性材料不因语义相近就自动算作证据。

本版 `goldPhrases` 在查看任何 holdout 检索结果前完成。正式运行前只允许做格式、文件存在性、gold 短语可定位性、`goldPhraseKinds` 与当前生产 lexical 路径一致性，以及确定性分块检查；禁止看 embedding、similarity、Top-K 或 gate 输出后再改 gold。

## 难负样本的词面交集与 pairFp

难负样本的 `goldPhrases` 固定为空，这一点被四处硬约束钉死：`HoldoutExperimentTests` 的 `validateDataset`（非 positive 且 gold 非空即抛）与 `legacyLabels`（非 positive 且出现块级金标命中即抛）、`HoldoutDatasetContractTests`（gold 必须为空且不得定义 `goldPhraseKinds`），以及 legacy 的 `ThresholdSweepExperimentTests`。

**但「gold 为空」不等于「简历与 JD 零词面交集」，也不代表难负样本的简历里没有该 JD 点名要求的能力。** 协议 §3 对难负样本的定义本身就是「同属研发、**共性词多但方向不对**」——共享术语是这一层的设计意图，而不是需要消除的缺陷。一个与目标 JD 零交集的配对实际上已经退化成普通负样本，诊断价值正好丢失。

冻结前用当前生产 lexical 路径（`ResumeRagService#extractKeywords` / `#matchKeywords` / hard-skill alias 边界匹配）对 12 个难负样本逐个做了确定性实测。该检查只运行词面通路与 `TextChunker`，未读取 embedding、similarity、Top-K 或 gate 输出，与本文件「标注约束」一节允许的运行前检查范围一致。结果：

| 分层 | 配对 | 说明 |
|---|---|---|
| JD 任职要求级交集 | `H07` `H09` `H10` | 简历确实具备该 JD 明确点名的能力项，见下 |
| 单点通用技能交集 | `H03` `H04` `H08` | 均为 `sharedHardSkills=[Python]`：JD 将 Python 列为任职要求，简历真实具备，但其余要求零覆盖 |
| 无实质交集 | `H01` `H02` `H05` `H06` `H11` `H12` | 命中项全部落在否定句、非专业段落、子串误命中或 JD 自身的切分噪声上 |

三个 JD 任职要求级交集的具体来源：

- `H07`（`sre-qin` × `security-operations`）：JD 要求 3「能分析 Windows、Linux、网络与身份相关日志」与要求 4「具备 Python、Bash…至少一种脚本/查询能力」，简历均具备，且「使用 Python 与 Shell 编写巡检、发布和**日志处理脚本**」直接对应日志分析场景。
- `H09`（`ux-designer-tang` × `product-manager-b2b`）：JD 要求 2「能独立输出 PRD、流程图、原型和验收规则」，简历具备其中的流程图与交互原型；JD 职责 1 的「需求访谈」对应简历的用户访谈。注意纯 lexical 探针查不出这一条——JD 那句被切成「原型和验收规则」整串，简历中没有逐字的该串。
- `H10`（`product-manager-xu` × `ux-designer-mobile`）：JD 要求 1「熟练使用 Figma」与要求 2 的「用户访谈」，简历均逐字具备。

因此这三个配对会稳定贡献 `pairFp`。**这一部分误判源于 `shouldMatch=false` 所蕴含的「期望零块过阈」在共享硬技能时不可达，不是检索器失灵。** 报告难负样本层的证据门指标时必须同时说明这一条，不得把这部分 `pairFp` 直接解读为阈值失效。

与此对应的两条边界：

- 没有把这些短语补进难负样本的 `goldPhrases`。除了被上述四处约束拒绝之外，更根本的原因是 runner 的证据门是二值的（`predictedEvidenceGate = !selected.isEmpty()`，`pairCorrect = predictedEvidenceGate == shouldMatch`），不存在「块级有证据但岗位级不匹配」的第三种状态。给难负样本补金标会让块级召回要求「必须命中」、pair 级要求「必须不命中」，同一个配对的两项指标互斥。
- 没有为消除交集而改写简历正文。删掉简历里真实且合理的技能描述以换取更好看的难负样本指标，等于用测试集反向调数据，正是协议 §4 要防的事；而且 `P07` / `P09` / `P10` 正样本用的是同一批文档，删词会连带削弱正样本质量。

new_domain 一侧也不存在替换配对的余地：该侧 6 个角色只构成三组「同大领域不同方向」对偶（SRE↔安全运营、UX↔B2B 产品、供应链计划↔采购寻源），六种组合已被 `H07`–`H12` 用尽，而三个问题配对恰好全部落在这一侧。

## 领域划分

`seen_domain`：Java 后端、Vue 前端、NLP/搜索算法、风险数据分析、护理、会计。

`new_domain`：SRE、安全运营、UX、B2B 产品、供应链计划、采购寻源。

公开 JD 来源与改写说明见 `SOURCES.md`。
