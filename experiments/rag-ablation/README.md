# 全文直喂 vs RAG 消融实验

这组实验复用 `../threshold-sweep/dataset` 的 18 组配对，比较两种送入生成模型前的简历证据策略：

- `full_text`：直接提供原始简历全文。
- `rag`：走真实 `TextChunker`、本地 GTE ONNX、Lucene 和 `ResumeRagService`，再按 raw similarity 阈值、boosted 排序与 Top-K 选择证据。

实验网格：

- chunk / overlap：`600/80`、`900/120`、`1200/160`
- Top-K：`1`、`3`、`5`
- min similarity：`0.65`、`0.70`、`0.72`、`0.75`、`0.80`

复现环境：

- JDK 21+ 与 PowerShell；Maven Wrapper 会处理 Java 依赖。
- 本地 `gte-multilingual-base-int8` 的 `tokenizer.json` 与 `model_int8.onnx`。
- 不需要 MySQL，也不会调用付费 LLM。

运行：

```powershell
.\run-ablation.ps1 `
  -JavaHome 'C:\Program Files\Java\jdk-25.0.2' `
  -TokenizerPath 'C:\path\to\tokenizer.json' `
  -ModelPath 'C:\path\to\model_int8.onnx'
```

若模型已位于 `jd-rag-resume-backend/models/gte-multilingual-base-int8/`，两个模型路径参数可以省略。

Arthur 当前 Windows 机器使用 JDK 25 的一次热运行约 22 秒；首次依赖下载、CPU 与磁盘速度都会影响实际耗时。

输出：

- `RESULTS.md`：自动生成的结论与参数网格。
- `results/config-metrics.csv|json`：策略 / 配置汇总。
- `results/pair-metrics.csv|json`：逐配对明细。
- `logs/ablation-console.log`、`logs/maven-ablation.log`：运行证据。

生产代码在没有任何 chunk 过阈时会直接落库 0 分与说明，不调用 LLM；因此结果也统计按该短路规则会触发多少次 LLM 请求。

边界：字符数只衡量简历证据载荷，不等于供应商计费 token；证据门控也不等于 LLM 最终匹配准确率。本实验默认不调用付费 LLM，请求数是按生产短路规则计算的，不是供应商账单。
