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

`RUN-LOG.md` 的 v1 仍保持“尚未运行”。上述结构修正和 gold kind 重标都发生在第一次正式报数之前，不消耗一次性 holdout 机会。

## 标注约束

gold 的问题是“哪些简历块含有能直接支撑目标 JD 的事实”，而不是“候选人整体像不像岗位”。因此教育背景、通用协作、自我评价、社团与课程性材料不因语义相近就自动算作证据。

本版 `goldPhrases` 在查看任何 holdout 检索结果前完成。正式运行前只允许做格式、文件存在性、gold 短语可定位性、`goldPhraseKinds` 与当前生产 lexical 路径一致性，以及确定性分块检查；禁止看 embedding、similarity、Top-K 或 gate 输出后再改 gold。

## 领域划分

`seen_domain`：Java 后端、Vue 前端、NLP/搜索算法、风险数据分析、护理、会计。

`new_domain`：SRE、安全运营、UX、B2B 产品、供应链计划、采购寻源。

公开 JD 来源与改写说明见 `SOURCES.md`。
