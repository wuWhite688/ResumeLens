# Holdout v1 dataset

状态：**待通过 PR 冻结，尚未执行正式 holdout run。**

## 组成

- 12 份全新合成简历。
- 12 份来自公开招聘页、经脱敏压缩改写的 JD。
- 36 个配对：`positive=12`、`negative=12`、`hard_negative=12`。
- 领域关系：`seen_domain=18`、`new_domain=18`。
- 每个正样本的 `goldPhrases` 都能在对应简历原文中直接定位；负样本与难负样本的 `goldPhrases` 固定为空。
- 生产分块配置下，每份简历至少会被切为 2 个文本块；这项检查只使用确定性的 `TextChunker` 边界规则，不读取向量、相似度或检索结果。

## 标注约束

gold 的问题是“哪些简历块含有能直接支撑目标 JD 的事实”，而不是“候选人整体像不像岗位”。因此教育背景、通用协作、自我评价、社团与课程性材料不因语义相近就自动算作证据。

本版 gold 在查看任何 holdout 检索结果前完成。正式运行前只允许做格式、文件存在性、gold 短语可定位性和确定性分块检查；禁止看 embedding、similarity、Top-K 或 gate 输出后再改 gold。

## 领域划分

`seen_domain`：Java 后端、Vue 前端、NLP/搜索算法、风险数据分析、护理、会计。

`new_domain`：SRE、安全运营、UX、B2B 产品、供应链计划、采购寻源。

公开 JD 来源与改写说明见 `SOURCES.md`。
