# Holdout v1 dataset

状态：**待通过 PR 冻结，尚未执行正式 holdout run。**

## 组成

- 12 份全新合成简历。
- 12 份来自公开招聘页、经脱敏压缩改写的 JD。
- 36 个配对：`positive=12`、`negative=12`、`hard_negative=12`。
- 领域关系：`seen_domain=18`、`new_domain=18`。
- 正样本的 `goldPhrases` 数量按实际证据选择，不要求固定个数；负样本与难负样本固定为空。
- seen/new 两组的普通负样本都把目标 JD 摊到 6 个岗位上，避免某一份 JD 过度主导负样本结果。

## goldPhrases 的两类来源

`goldPhrases` 仍然只是用来派生“哪些简历块是相关块”的定位锚点，所有短语都必须在对应简历原文中逐字出现。为了把“明显词面重合”和“靠语义对应的事实”区分开，正样本额外冻结 `goldPhraseKinds`：

- `lexical`：该短语同时逐字出现在简历和 JD 中，例如 `Spring Boot`、`Kubernetes`。
- `semantic`：该短语逐字出现在简历中，但不逐字出现在 JD 中；它由人工判断为能支撑 JD 要求的事实，例如“客户端请求号建立幂等约束”对应 JD 的重复请求/幂等场景。

这个分类是**诊断标签，不是新的金标生成规则**：块仍然只要命中任一 `goldPhrase` 就算 gold relevant。后续结果可以分别观察 lexical / semantic 证据覆盖，但不能因为 semantic 短语所在块被召回，就直接声称“语义检索胜过 BM25”——同一个 900 字块里仍可能同时含有词面硬技能。

## 与分块配置的关系

v1 在当前生产配置 `chunkSize=900`、`overlap=120` 下做了确定性静态检查：每份简历至少会被切成 2 个文本块，且每个正样本至少有 1 个 gold-relevant chunk。这项检查只运行 `TextChunker`，不读取 embedding、similarity、Top-K 或 gate 输出。

**这意味着 v1 的诊断价值依赖当前分块尺度。** 协议里“gold 会随 chunk 配置重新派生”仍然成立，但如果未来生产配置改大（例如 1200/160）导致部分简历退化成单块，v1 的块级指标会失去区分度。遇到这种情况不要回头拉长或改写 v1 文档；v1 已冻结，应新建新的 holdout 版本。

`HoldoutDatasetContractTests` 会在普通测试中检查上述前提。如果未来生产分块配置使 v1 不再满足至少 2 块，测试会显式失败，提醒创建新版本，而不是静默继续报块级指标。

## 标注约束

gold 的问题是“哪些简历块含有能直接支撑目标 JD 的事实”，而不是“候选人整体像不像岗位”。因此教育背景、通用协作、自我评价、社团与课程性材料不因语义相近就自动算作证据。

本版 gold 与 `goldPhraseKinds` 在查看任何 holdout 检索结果前完成。正式运行前只允许做格式、文件存在性、gold 短语可定位性、lexical/semantic 字面关系和确定性分块检查；禁止看 embedding、similarity、Top-K 或 gate 输出后再改 gold。

## 领域划分

`seen_domain`：Java 后端、Vue 前端、NLP/搜索算法、风险数据分析、护理、会计。

`new_domain`：SRE、安全运营、UX、B2B 产品、供应链计划、采购寻源。

公开 JD 来源与改写说明见 `SOURCES.md`。
