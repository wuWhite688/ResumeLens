# 实验代码位置

JUnit 实验入口（走真实 `TextChunker` + `ClsOnnxEmbeddingModel` + `LuceneVectorIndex` + `ResumeRagService#retrieve`）：

`jd-rag-resume-backend/src/test/java/com/arthur/jdragresume/rag/ThresholdSweepExperimentTests.java`

该类用环境变量守门，默认不进常规 CI：

- `RUN_THRESHOLD_SWEEP=true`：完整阈值扫描
- `RUN_THRESHOLD_SWEEP_PREVIEW=true`：只跑分块与金标预览，不加载 ONNX

从本目录的上一级运行：

```powershell
.\run-preview.ps1
.\run-sweep.ps1
```
