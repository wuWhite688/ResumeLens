package com.arthur.jdragresume.rag;

import com.arthur.jdragresume.entity.AppUser;
import com.arthur.jdragresume.entity.JobDescription;
import com.arthur.jdragresume.entity.Resume;
import com.arthur.jdragresume.entity.ResumeChunk;
import com.arthur.jdragresume.repository.ResumeChunkRepository;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.io.PrintStream;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Offline threshold sweep against the real retrieve path.
 * Gated so regular {@code mvnw verify} does not download or load the ONNX model.
 */
class ThresholdSweepExperimentTests {
    private static final String REMOTE_TOKENIZER =
            "https://huggingface.co/onnx-community/gte-multilingual-base/resolve/main/tokenizer.json";
    private static final String REMOTE_MODEL =
            "https://huggingface.co/onnx-community/gte-multilingual-base/resolve/main/onnx/model_int8.onnx";
    private static final List<Integer> ABLATION_CHUNK_SIZES = List.of(600, 900, 1200);
    private static final List<Integer> ABLATION_TOP_KS = List.of(1, 3, 5);
    private static final List<Double> ABLATION_THRESHOLDS = List.of(0.65, 0.70, 0.72, 0.75, 0.80);

    @Test
    @EnabledIfEnvironmentVariable(named = "RUN_THRESHOLD_SWEEP_PREVIEW", matches = "true")
    void dumpChunksForAnnotation() throws Exception {
        Path sweepDir = resolveSweepDir();
        ObjectMapper mapper = mapper();
        DatasetFile dataset = loadDataset(sweepDir, mapper);
        TextChunker chunker = new TextChunker(productionProperties());
        Map<String, Object> preview = buildChunkPreview(sweepDir, dataset, chunker);
        Path out = sweepDir.resolve("dataset").resolve("labeled-chunks.preview.json");
        Files.createDirectories(out.getParent());
        mapper.writerWithDefaultPrettyPrinter().writeValue(out.toFile(), preview);
        System.out.println("wrote chunk preview: " + out.toAbsolutePath());
        assertFalse(dataset.pairs().isEmpty(), "dataset pairs must not be empty");
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "RUN_THRESHOLD_SWEEP", matches = "true")
    @Timeout(value = 40, unit = TimeUnit.MINUTES)
    void sweepMinSimilarityThresholds() throws Exception {
        Path sweepDir = resolveSweepDir();
        Path logsDir = sweepDir.resolve("logs");
        Path resultsDir = sweepDir.resolve("results");
        Files.createDirectories(logsDir);
        Files.createDirectories(resultsDir);

        ObjectMapper mapper = mapper();
        DatasetFile dataset = loadDataset(sweepDir, mapper);
        assertFalse(dataset.pairs().isEmpty());

        Path consoleLog = logsDir.resolve("sweep-console.log");
        try (PrintStream log = new PrintStream(Files.newOutputStream(consoleLog), true, StandardCharsets.UTF_8)) {
            log("=== threshold sweep start " + Instant.now() + " ===", log);
            log("sweepDir=" + sweepDir.toAbsolutePath(), log);

            RagProperties properties = productionProperties();
            TextChunker chunker = new TextChunker(properties);
            Map<String, Object> preview = buildChunkPreview(sweepDir, dataset, chunker);
            Path labeledPath = sweepDir.resolve("dataset").resolve("labeled-chunks.json");
            mapper.writerWithDefaultPrettyPrinter().writeValue(labeledPath.toFile(), preview);
            log("wrote labeled chunks: " + labeledPath, log);

            String tokenizerUri = resolveResource(
                    "RAG_EMBEDDING_TOKENIZER_URI",
                    Path.of("models", "gte-multilingual-base-int8", "tokenizer.json"),
                    REMOTE_TOKENIZER
            );
            String modelUri = resolveResource(
                    "RAG_EMBEDDING_MODEL_URI",
                    Path.of("models", "gte-multilingual-base-int8", "model_int8.onnx"),
                    REMOTE_MODEL
            );
            log("tokenizerUri=" + tokenizerUri, log);
            log("modelUri=" + modelUri, log);
            assertLocalModelPresent(tokenizerUri, modelUri, log);

            Path luceneDir = Files.createTempDirectory("threshold-sweep-lucene");
            Map<Long, List<ResumeChunk>> chunkStore = new ConcurrentHashMap<>();
            ResumeChunkRepository repository = inMemoryRepository(chunkStore);
            ResumeIndexStore indexStore = new ResumeIndexStore(repository);
            ObjectMapper embeddingMapper = new ObjectMapper();

            ClsOnnxEmbeddingModel embeddingModel = new ClsOnnxEmbeddingModel(
                    tokenizerUri,
                    modelUri,
                    properties.getModelOutputName(),
                    Map.of(
                            "padding", "true",
                            "truncation", "true",
                            "modelMaxLength", String.valueOf(properties.getMaxLength()),
                            "maxLength", String.valueOf(properties.getMaxLength())
                    ),
                    properties.getEmbeddingDimensions()
            );

            List<Map<String, Object>> retrieveRaw = new ArrayList<>();
            List<Map<String, Object>> chunkScoreRows = new ArrayList<>();
            List<Map<String, Object>> pairRows = new ArrayList<>();
            List<Map<String, Object>> thresholdRows = new ArrayList<>();
            boolean completed = false;
            String failure = null;

            try {
                long loadStarted = System.nanoTime();
                embeddingModel.afterPropertiesSet();
                log(String.format(Locale.ROOT, "onnx model loaded in %.1fs", elapsedSeconds(loadStarted)), log);

                try (LuceneVectorIndex vectorIndex = new LuceneVectorIndex(embeddingMapper, luceneDir.toString())) {
                    ResumeRagService service = new ResumeRagService(
                            embeddingModel,
                            repository,
                            chunker,
                            properties,
                            embeddingMapper,
                            indexStore,
                            vectorIndex
                    );

                    AppUser user = experimentUser();
                    Map<String, Resume> resumes = materializeResumes(sweepDir, dataset, user);
                    Map<String, JobDescription> jobs = materializeJobs(sweepDir, dataset, user);
                    Map<String, List<LabeledChunk>> labeledByPair = labeledChunksByPair(preview);

                    List<Double> thresholds = sweepThresholds();
                    log("pairs=" + dataset.pairs().size() + " thresholds=" + thresholds, log);

                    int pairOrdinal = 0;
                    for (PairSpec pair : dataset.pairs()) {
                        pairOrdinal += 1;
                        Resume resume = resumes.get(pair.resumeId());
                        JobDescription job = jobs.get(pair.jobId());
                        if (resume == null || job == null) {
                            throw new IllegalStateException("missing resume/job for pair " + pair.id());
                        }
                        List<LabeledChunk> labeled = labeledByPair.getOrDefault(pair.id(), List.of());

                        for (double threshold : thresholds) {
                            properties.setMinSimilarity(threshold);
                            long started = System.nanoTime();
                            List<RetrievedChunk> retrieved;
                            try {
                                retrieved = service.retrieve(user, resume, job);
                            } catch (RuntimeException ex) {
                                failure = "retrieve failed pair=" + pair.id()
                                        + " threshold=" + formatThreshold(threshold)
                                        + " type=" + ex.getClass().getName()
                                        + " message=" + ex.getMessage();
                                log(failure, log);
                                throw ex;
                            }
                            double seconds = elapsedSeconds(started);

                            PairThresholdMetrics metrics = scorePair(pair, labeled, retrieved, threshold);
                            Map<String, Object> row = metrics.toRow(pair, resume.getId(), seconds, threshold);
                            pairRows.add(row);

                            Map<String, Object> raw = new LinkedHashMap<>();
                            raw.put("pairId", pair.id());
                            raw.put("type", pair.type());
                            raw.put("threshold", threshold);
                            raw.put("elapsedSeconds", seconds);
                            raw.put("resumeId", pair.resumeId());
                            raw.put("jobId", pair.jobId());
                            raw.put("metrics", row);
                            raw.put("retrieved", retrieved.stream().map(ThresholdSweepExperimentTests::retrievedToMap).toList());
                            retrieveRaw.add(raw);
                            for (RetrievedChunk chunk : retrieved) {
                                Map<String, Object> score = new LinkedHashMap<>();
                                score.put("pairId", pair.id());
                                score.put("type", pair.type());
                                score.put("threshold", threshold);
                                score.put("chunkIndex", chunk.chunkIndex());
                                score.put("section", chunk.section());
                                score.put("rawSimilarity", chunk.rawSimilarity());
                                score.put("boostedSimilarity", chunk.similarity());
                                score.put("kept", chunk.kept());
                                score.put("status", chunk.status());
                                score.put("boostKeywords", String.join("|", chunk.boostKeywords()));
                                score.put("goldRelevant", labeled.stream().anyMatch(
                                        item -> item.index() == chunk.chunkIndex() && item.relevant()
                                ));
                                chunkScoreRows.add(score);
                            }

                            log(String.format(
                                    Locale.ROOT,
                                    "[%d/%d] %s %s thr=%s kept=%d pass=%d gold=%d tp=%d fp=%d fn=%d boostCross=%d %.2fs",
                                    pairOrdinal,
                                    dataset.pairs().size(),
                                    pair.id(),
                                    pair.type(),
                                    formatThreshold(threshold),
                                    metrics.kept,
                                    metrics.pass,
                                    metrics.goldRelevant,
                                    metrics.tp,
                                    metrics.fp,
                                    metrics.fn,
                                    metrics.boostWouldCross,
                                    seconds
                            ), log);
                        }
                    }

                    for (double threshold : thresholds) {
                        thresholdRows.add(aggregate(pairRows, dataset.pairs(), threshold));
                    }

                    Path jsonl = logsDir.resolve("retrieve-raw.jsonl");
                    ObjectMapper jsonlMapper = new ObjectMapper();
                    try (var writer = Files.newBufferedWriter(jsonl, StandardCharsets.UTF_8)) {
                        for (Map<String, Object> raw : retrieveRaw) {
                            writer.write(jsonlMapper.writeValueAsString(raw));
                            writer.newLine();
                        }
                    }
                    Path chunkCsv = resultsDir.resolve("chunk-scores.csv");
                    writeCsv(chunkCsv, chunkScoreRows, CHUNK_CSV_COLUMNS);

                    Path pairCsv = resultsDir.resolve("pair-by-threshold.csv");
                    writeCsv(pairCsv, pairRows, PAIR_CSV_COLUMNS);

                    Path summaryCsv = resultsDir.resolve("threshold-metrics.csv");
                    writeCsv(summaryCsv, thresholdRows, THRESHOLD_CSV_COLUMNS);

                    Path summaryJson = resultsDir.resolve("threshold-metrics.json");
                    mapper.writerWithDefaultPrettyPrinter().writeValue(summaryJson.toFile(), thresholdRows);

                    Path pairJson = resultsDir.resolve("pair-by-threshold.json");
                    mapper.writerWithDefaultPrettyPrinter().writeValue(pairJson.toFile(), pairRows);

                    Path autoReport = resultsDir.resolve("auto-report.md");
                    Files.writeString(autoReport, renderAutoReport(thresholdRows, pairRows, dataset), StandardCharsets.UTF_8);

                    log("wrote " + jsonl.toAbsolutePath(), log);
                    log("wrote " + summaryCsv.toAbsolutePath(), log);
                    log("wrote " + autoReport.toAbsolutePath(), log);
                    completed = true;
                }
            } catch (Exception ex) {
                if (failure == null) {
                    failure = ex.getClass().getName() + ": " + ex.getMessage();
                }
                log("EXPERIMENT FAILED: " + failure, log);
                Files.writeString(
                        resultsDir.resolve("FAILURE.txt"),
                        failure + System.lineSeparator(),
                        StandardCharsets.UTF_8
                );
                throw ex;
            } finally {
                embeddingModel.destroy();
                log("=== threshold sweep end " + Instant.now() + " completed=" + completed + " ===", log);
            }

            assertTrue(completed, "sweep must complete");
            assertTrue(Files.size(resultsDir.resolve("threshold-metrics.csv")) > 0);
        }
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "RUN_RAG_ABLATION", matches = "true")
    @Timeout(value = 40, unit = TimeUnit.MINUTES)
    void compareFullTextWithRagAcrossConfigurations() throws Exception {
        Path sweepDir = resolveSweepDir();
        Path ablationDir = resolveAblationDir(sweepDir);
        Path logsDir = ablationDir.resolve("logs");
        Path resultsDir = ablationDir.resolve("results");
        Files.createDirectories(logsDir);
        Files.createDirectories(resultsDir);

        ObjectMapper mapper = mapper();
        DatasetFile dataset = loadDataset(sweepDir, mapper);
        assertFalse(dataset.pairs().isEmpty());

        Path consoleLog = logsDir.resolve("ablation-console.log");
        try (PrintStream log = new PrintStream(Files.newOutputStream(consoleLog), true, StandardCharsets.UTF_8)) {
            log("=== RAG ablation start " + Instant.now() + " ===", log);
            log("dataset=" + sweepDir.resolve("dataset").toAbsolutePath(), log);
            log("output=" + ablationDir.toAbsolutePath(), log);

            RagProperties properties = productionProperties();
            TextChunker chunker = new TextChunker(properties);
            String tokenizerUri = resolveResource(
                    "RAG_EMBEDDING_TOKENIZER_URI",
                    Path.of("models", "gte-multilingual-base-int8", "tokenizer.json"),
                    REMOTE_TOKENIZER
            );
            String modelUri = resolveResource(
                    "RAG_EMBEDDING_MODEL_URI",
                    Path.of("models", "gte-multilingual-base-int8", "model_int8.onnx"),
                    REMOTE_MODEL
            );
            assertLocalModelPresent(tokenizerUri, modelUri, log);

            Path luceneDir = Files.createTempDirectory("rag-ablation-lucene");
            Map<Long, List<ResumeChunk>> chunkStore = new ConcurrentHashMap<>();
            ResumeChunkRepository repository = inMemoryRepository(chunkStore);
            ResumeIndexStore indexStore = new ResumeIndexStore(repository);
            ObjectMapper embeddingMapper = new ObjectMapper();
            ClsOnnxEmbeddingModel embeddingModel = new ClsOnnxEmbeddingModel(
                    tokenizerUri,
                    modelUri,
                    properties.getModelOutputName(),
                    Map.of(
                            "padding", "true",
                            "truncation", "true",
                            "modelMaxLength", String.valueOf(properties.getMaxLength()),
                            "maxLength", String.valueOf(properties.getMaxLength())
                    ),
                    properties.getEmbeddingDimensions()
            );

            List<Map<String, Object>> pairRows = new ArrayList<>();
            boolean completed = false;
            try {
                long loadStarted = System.nanoTime();
                embeddingModel.afterPropertiesSet();
                log(String.format(Locale.ROOT, "ONNX model loaded in %.1fs", elapsedSeconds(loadStarted)), log);

                try (LuceneVectorIndex vectorIndex = new LuceneVectorIndex(embeddingMapper, luceneDir.toString())) {
                    ResumeRagService service = new ResumeRagService(
                            embeddingModel,
                            repository,
                            chunker,
                            properties,
                            embeddingMapper,
                            indexStore,
                            vectorIndex
                    );
                    AppUser user = experimentUser();
                    Map<String, Resume> resumes = materializeResumes(sweepDir, dataset, user);
                    Map<String, JobDescription> jobs = materializeJobs(sweepDir, dataset, user);

                    for (int chunkSize : ABLATION_CHUNK_SIZES) {
                        int chunkOverlap = scaledOverlap(chunkSize);
                        properties.setChunkSize(chunkSize);
                        properties.setChunkOverlap(chunkOverlap);
                        // Retrieve every chunk once. The grid below reapplies the production
                        // raw-threshold and boosted-order Top-K rules without re-embedding queries.
                        properties.setTopK(80);
                        properties.setMinSimilarity(0.0);

                        Map<String, Object> preview = buildChunkPreview(sweepDir, dataset, chunker);
                        Map<String, List<LabeledChunk>> labeledByPair = labeledChunksByPair(preview);
                        log("chunkSize=" + chunkSize + " overlap=" + chunkOverlap, log);

                        for (PairSpec pair : dataset.pairs()) {
                            Resume resume = resumes.get(pair.resumeId());
                            JobDescription job = jobs.get(pair.jobId());
                            List<LabeledChunk> labeled = labeledByPair.getOrDefault(pair.id(), List.of());
                            List<RetrievedChunk> ranked = service.retrieve(user, resume, job);
                            if (ranked.size() != labeled.size()) {
                                throw new IllegalStateException(
                                        "expected every chunk in ablation pair=" + pair.id()
                                                + " chunkSize=" + chunkSize
                                                + " labeled=" + labeled.size()
                                                + " retrieved=" + ranked.size()
                                );
                            }

                            pairRows.add(scoreAblationPair(
                                    "full_text",
                                    pair,
                                    labeled,
                                    ranked,
                                    chunkSize,
                                    chunkOverlap,
                                    0,
                                    0.0,
                                    resume.getRawText().length(),
                                    utf8Bytes(resume.getRawText()),
                                    resume.getRawText().length(),
                                    utf8Bytes(resume.getRawText())
                            ));

                            for (int topK : ABLATION_TOP_KS) {
                                for (double threshold : ABLATION_THRESHOLDS) {
                                    List<RetrievedChunk> selected = selectRagEvidence(ranked, topK, threshold);
                                    String evidence = selected.stream()
                                            .map(RetrievedChunk::content)
                                            .collect(java.util.stream.Collectors.joining("\n\n"));
                                    pairRows.add(scoreAblationPair(
                                            "rag",
                                            pair,
                                            labeled,
                                            selected,
                                            chunkSize,
                                            chunkOverlap,
                                            topK,
                                            threshold,
                                            evidence.length(),
                                            utf8Bytes(evidence),
                                            resume.getRawText().length(),
                                            utf8Bytes(resume.getRawText())
                                    ));
                                }
                            }
                        }
                    }

                    List<Map<String, Object>> configRows = aggregateAblation(pairRows);
                    mapper.writerWithDefaultPrettyPrinter().writeValue(
                            resultsDir.resolve("pair-metrics.json").toFile(), pairRows
                    );
                    mapper.writerWithDefaultPrettyPrinter().writeValue(
                            resultsDir.resolve("config-metrics.json").toFile(), configRows
                    );
                    writeCsv(resultsDir.resolve("pair-metrics.csv"), pairRows, ABLATION_PAIR_CSV_COLUMNS);
                    writeCsv(resultsDir.resolve("config-metrics.csv"), configRows, ABLATION_CONFIG_CSV_COLUMNS);
                    Files.writeString(
                            ablationDir.resolve("RESULTS.md"),
                            renderAblationReport(configRows, dataset),
                            StandardCharsets.UTF_8
                    );
                    completed = true;
                    log("wrote " + ablationDir.resolve("RESULTS.md").toAbsolutePath(), log);
                }
            } finally {
                embeddingModel.destroy();
                deleteRecursively(luceneDir);
                log("=== RAG ablation end " + Instant.now() + " completed=" + completed + " ===", log);
            }
        }

        assertTrue(Files.size(resultsDir.resolve("config-metrics.csv")) > 0);
        assertTrue(Files.size(ablationDir.resolve("RESULTS.md")) > 0);
    }

    private static final List<String> ABLATION_PAIR_CSV_COLUMNS = List.of(
            "strategy", "pairId", "type", "shouldMatch", "resumeId", "jobId",
            "chunkSize", "chunkOverlap", "topK", "threshold",
            "chunkCount", "goldRelevant", "selectedChunks", "tp", "fp", "fn",
            "chunkPrecision", "chunkRecall", "chunkF1", "predictedEvidenceGate", "pairCorrect",
            "evidenceChars", "evidenceUtf8Bytes", "fullTextChars", "fullTextUtf8Bytes"
    );

    private static final List<String> ABLATION_CONFIG_CSV_COLUMNS = List.of(
            "strategy", "productionConfig", "chunkSize", "chunkOverlap", "topK", "threshold", "pairs",
            "chunkPrecision", "chunkRecall", "chunkF1",
            "pairPrecision", "pairRecall", "pairF1", "pairAccuracy", "pairTp", "pairFp", "pairFn", "pairTn",
            "llmRequestsTriggered", "llmRequestReduction",
            "meanSelectedChunks", "positiveMeanSelectedChunks", "negativeMeanSelectedChunks",
            "payloadChars", "fullTextChars", "payloadRatio", "payloadReduction",
            "positivePayloadRatio", "negativePayloadRatio"
    );

    private static Map<String, Object> scoreAblationPair(
            String strategy,
            PairSpec pair,
            List<LabeledChunk> labeled,
            List<RetrievedChunk> selected,
            int chunkSize,
            int chunkOverlap,
            int topK,
            double threshold,
            int evidenceChars,
            int evidenceUtf8Bytes,
            int fullTextChars,
            int fullTextUtf8Bytes
    ) {
        Set<Integer> selectedIndexes = new HashSet<>();
        selected.forEach(chunk -> selectedIndexes.add(chunk.chunkIndex()));
        int goldRelevant = 0;
        int tp = 0;
        int fp = 0;
        int fn = 0;
        for (LabeledChunk chunk : labeled) {
            if (chunk.relevant()) {
                goldRelevant += 1;
            }
            boolean picked = selectedIndexes.contains(chunk.index());
            if (picked && chunk.relevant()) {
                tp += 1;
            } else if (picked) {
                fp += 1;
            } else if (chunk.relevant()) {
                fn += 1;
            }
        }
        Rates rates = rates(tp, fp, fn);
        boolean predictedEvidenceGate = !selected.isEmpty();

        Map<String, Object> row = new LinkedHashMap<>();
        row.put("strategy", strategy);
        row.put("pairId", pair.id());
        row.put("type", pair.type());
        row.put("shouldMatch", pair.shouldMatch());
        row.put("resumeId", pair.resumeId());
        row.put("jobId", pair.jobId());
        row.put("chunkSize", chunkSize);
        row.put("chunkOverlap", chunkOverlap);
        row.put("topK", topK);
        row.put("threshold", threshold);
        row.put("chunkCount", labeled.size());
        row.put("goldRelevant", goldRelevant);
        row.put("selectedChunks", selected.size());
        row.put("tp", tp);
        row.put("fp", fp);
        row.put("fn", fn);
        row.put("chunkPrecision", rates.precision());
        row.put("chunkRecall", rates.recall());
        row.put("chunkF1", rates.f1());
        row.put("predictedEvidenceGate", predictedEvidenceGate);
        row.put("pairCorrect", predictedEvidenceGate == pair.shouldMatch());
        row.put("evidenceChars", evidenceChars);
        row.put("evidenceUtf8Bytes", evidenceUtf8Bytes);
        row.put("fullTextChars", fullTextChars);
        row.put("fullTextUtf8Bytes", fullTextUtf8Bytes);
        return row;
    }

    private static List<RetrievedChunk> selectRagEvidence(
            List<RetrievedChunk> ranked,
            int topK,
            double threshold
    ) {
        List<RetrievedChunk> selected = new ArrayList<>();
        for (RetrievedChunk chunk : ranked) {
            if (chunk.rawSimilarity() >= threshold && selected.size() < topK) {
                selected.add(chunk);
            }
        }
        return selected;
    }

    private static List<Map<String, Object>> aggregateAblation(List<Map<String, Object>> pairRows) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (int chunkSize : ABLATION_CHUNK_SIZES) {
            List<Map<String, Object>> fullRows = pairRows.stream()
                    .filter(row -> "full_text".equals(row.get("strategy")))
                    .filter(row -> intValue(row, "chunkSize") == chunkSize)
                    .toList();
            out.add(aggregateAblationRows(fullRows, "full_text", chunkSize, scaledOverlap(chunkSize), 0, 0.0));

            for (int topK : ABLATION_TOP_KS) {
                for (double threshold : ABLATION_THRESHOLDS) {
                    List<Map<String, Object>> rows = pairRows.stream()
                            .filter(row -> "rag".equals(row.get("strategy")))
                            .filter(row -> intValue(row, "chunkSize") == chunkSize)
                            .filter(row -> intValue(row, "topK") == topK)
                            .filter(row -> Double.compare(doubleValue(row, "threshold"), threshold) == 0)
                            .toList();
                    out.add(aggregateAblationRows(
                            rows, "rag", chunkSize, scaledOverlap(chunkSize), topK, threshold
                    ));
                }
            }
        }
        return out;
    }

    private static Map<String, Object> aggregateAblationRows(
            List<Map<String, Object>> rows,
            String strategy,
            int chunkSize,
            int chunkOverlap,
            int topK,
            double threshold
    ) {
        if (rows.isEmpty()) {
            throw new IllegalStateException("missing ablation rows for " + strategy + " chunkSize=" + chunkSize);
        }
        int tp = sumInt(rows, "tp");
        int fp = sumInt(rows, "fp");
        int fn = sumInt(rows, "fn");
        Rates chunkRates = rates(tp, fp, fn);

        int pairTp = 0;
        int pairFp = 0;
        int pairFn = 0;
        int pairTn = 0;
        int selected = 0;
        int positiveSelected = 0;
        int negativeSelected = 0;
        int positiveCount = 0;
        int negativeCount = 0;
        long payloadChars = 0;
        long fullTextChars = 0;
        long positivePayloadChars = 0;
        long positiveFullTextChars = 0;
        long negativePayloadChars = 0;
        long negativeFullTextChars = 0;

        for (Map<String, Object> row : rows) {
            boolean shouldMatch = Boolean.TRUE.equals(row.get("shouldMatch"));
            boolean predicted = Boolean.TRUE.equals(row.get("predictedEvidenceGate"));
            if (shouldMatch && predicted) {
                pairTp += 1;
            } else if (!shouldMatch && predicted) {
                pairFp += 1;
            } else if (shouldMatch) {
                pairFn += 1;
            } else {
                pairTn += 1;
            }
            int rowSelected = intValue(row, "selectedChunks");
            long rowPayload = intValue(row, "evidenceChars");
            long rowFullText = intValue(row, "fullTextChars");
            selected += rowSelected;
            payloadChars += rowPayload;
            fullTextChars += rowFullText;
            if (shouldMatch) {
                positiveCount += 1;
                positiveSelected += rowSelected;
                positivePayloadChars += rowPayload;
                positiveFullTextChars += rowFullText;
            } else {
                negativeCount += 1;
                negativeSelected += rowSelected;
                negativePayloadChars += rowPayload;
                negativeFullTextChars += rowFullText;
            }
        }

        Rates pairRates = rates(pairTp, pairFp, pairFn);
        double payloadRatio = ratio(payloadChars, fullTextChars);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("strategy", strategy);
        out.put("productionConfig", "rag".equals(strategy)
                && chunkSize == 900 && topK == 5 && Double.compare(threshold, 0.72) == 0);
        out.put("chunkSize", chunkSize);
        out.put("chunkOverlap", chunkOverlap);
        out.put("topK", topK);
        out.put("threshold", threshold);
        out.put("pairs", rows.size());
        out.put("chunkPrecision", chunkRates.precision());
        out.put("chunkRecall", chunkRates.recall());
        out.put("chunkF1", chunkRates.f1());
        out.put("pairPrecision", pairRates.precision());
        out.put("pairRecall", pairRates.recall());
        out.put("pairF1", pairRates.f1());
        out.put("pairAccuracy", ratio(pairTp + pairTn, rows.size()));
        out.put("pairTp", pairTp);
        out.put("pairFp", pairFp);
        out.put("pairFn", pairFn);
        out.put("pairTn", pairTn);
        out.put("llmRequestsTriggered", pairTp + pairFp);
        out.put("llmRequestReduction", 1.0 - ratio(pairTp + pairFp, rows.size()));
        out.put("meanSelectedChunks", ratio(selected, rows.size()));
        out.put("positiveMeanSelectedChunks", ratio(positiveSelected, positiveCount));
        out.put("negativeMeanSelectedChunks", ratio(negativeSelected, negativeCount));
        out.put("payloadChars", payloadChars);
        out.put("fullTextChars", fullTextChars);
        out.put("payloadRatio", payloadRatio);
        out.put("payloadReduction", 1.0 - payloadRatio);
        out.put("positivePayloadRatio", ratio(positivePayloadChars, positiveFullTextChars));
        out.put("negativePayloadRatio", ratio(negativePayloadChars, negativeFullTextChars));
        return out;
    }

    private static String renderAblationReport(
            List<Map<String, Object>> configRows,
            DatasetFile dataset
    ) {
        Map<String, Object> full = findAblationConfig(configRows, "full_text", 900, 0, 0.0);
        Map<String, Object> production = findAblationConfig(configRows, "rag", 900, 5, 0.72);

        StringBuilder md = new StringBuilder();
        md.append("# 全文直喂 vs RAG 消融实验\n\n");
        md.append("生成时间：").append(Instant.now()).append("\n\n");
        md.append("数据集复用 `../threshold-sweep/dataset`：")
                .append(dataset.resumes().size()).append(" 份中文简历、")
                .append(dataset.jobs().size()).append(" 份 JD、")
                .append(dataset.pairs().size()).append(" 组配对（6 正、6 负、6 难负）。\n\n");
        md.append("本实验比较的是**送入生成模型前的简历证据选择**。全文 baseline 直接提供原始简历；RAG 走真实 ")
                .append("`TextChunker + ClsOnnxEmbeddingModel + LuceneVectorIndex + ResumeRagService`，")
                .append("再按生产规则使用 raw similarity 过阈、boosted similarity 排序和 Top-K 截断。\n\n");

        md.append("## 生产配置对照\n\n");
        md.append("| 策略 | 块P | 块R | 块F1 | 证据门P | 证据门R | 证据门F1 | 门控准确率 | 触发LLM请求 | 请求减少 | 全体内容比 | 正样本内容比 | 负样本内容比 | 正样本均选块 |\n");
        md.append("|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|\n");
        appendAblationSummaryRow(md, "全文 baseline", full);
        appendAblationSummaryRow(md, "RAG（900 / Top-5 / 0.72）", production);

        md.append("\n内容比 = 该策略简历证据字符数 / 全文字符数；不含两边共享的 JD、system prompt，")
                .append("也不把字符数冒充供应商计费 token。RAG 的 chunk 头和规则元数据同样未计入，因此这里对 RAG 的体积估计偏保守。\n\n");

        md.append("## RAG 参数网格\n\n");
        md.append("| chunk / overlap | Top-K | 阈值 | 块F1 | 证据门F1 | 门控准确率 | LLM请求减少 | 全体内容比 | 正样本内容比 | 负样本内容比 |\n");
        md.append("|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|\n");
        configRows.stream()
                .filter(row -> "rag".equals(row.get("strategy")))
                .forEach(row -> md.append("| ")
                        .append(row.get("chunkSize")).append(" / ").append(row.get("chunkOverlap"))
                        .append(" | ").append(row.get("topK"))
                        .append(" | ").append(formatThreshold(doubleValue(row, "threshold")))
                        .append(" | ").append(fmt(doubleValue(row, "chunkF1")))
                        .append(" | ").append(fmt(doubleValue(row, "pairF1")))
                        .append(" | ").append(fmt(doubleValue(row, "pairAccuracy")))
                        .append(" | ").append(fmt(doubleValue(row, "llmRequestReduction")))
                        .append(" | ").append(fmt(doubleValue(row, "payloadRatio")))
                        .append(" | ").append(fmt(doubleValue(row, "positivePayloadRatio")))
                        .append(" | ").append(fmt(doubleValue(row, "negativePayloadRatio")))
                        .append(" |\n"));

        md.append("\n## 可以说什么，不能说什么\n\n");
        md.append("- 在这组短简历上，生产 RAG 的正样本内容比为 ")
                .append(fmt(doubleValue(production, "positivePayloadRatio")))
                .append("，正样本平均选择 ")
                .append(fmt(doubleValue(production, "positiveMeanSelectedChunks")))
                .append(" 个块；因此不能声称它靠缩短**对口短简历**显著省 token。\n");
        md.append("- 它的可测收益是把负样本与难负样本的简历证据内容比降到 ")
                .append(fmt(doubleValue(production, "negativePayloadRatio")))
                .append("；配合生产代码的零证据短路，本数据集触发的 LLM 请求从 ")
                .append(full.get("llmRequestsTriggered")).append(" 次降到 ")
                .append(production.get("llmRequestsTriggered")).append(" 次，减少 ")
                .append(percent(doubleValue(production, "llmRequestReduction")))
                .append("，同时保留 chunk 引用链。\n");
        md.append("- `证据门` 只表示是否有块过阈，不是 LLM 的最终匹配分类；`触发LLM请求` 是按零证据短路规则计算的请求次数。")
                .append("本实验没有调用付费 LLM，所以不能把门控准确率写成端到端模型准确率，也没有真实供应商 token 账单。\n");
        md.append("- 参数网格里存在同集分数高于生产配置的组合，但调参与评估使用的是同一组构造数据；")
                .append("直接据此更换默认参数会产生选择偏差，必须先补独立 holdout 再决定。\n");
        md.append("- 数据为 18 组构造配对且没有独立 holdout。参数网格用于小样本校准与方案解释，")
                .append("不能宣称 0.72、900 或 Top-5 是普适最优。\n");
        return md.toString();
    }

    private static void appendAblationSummaryRow(
            StringBuilder md,
            String label,
            Map<String, Object> row
    ) {
        md.append("| ").append(label)
                .append(" | ").append(fmt(doubleValue(row, "chunkPrecision")))
                .append(" | ").append(fmt(doubleValue(row, "chunkRecall")))
                .append(" | ").append(fmt(doubleValue(row, "chunkF1")))
                .append(" | ").append(fmt(doubleValue(row, "pairPrecision")))
                .append(" | ").append(fmt(doubleValue(row, "pairRecall")))
                .append(" | ").append(fmt(doubleValue(row, "pairF1")))
                .append(" | ").append(fmt(doubleValue(row, "pairAccuracy")))
                .append(" | ").append(row.get("llmRequestsTriggered"))
                .append(" | ").append(fmt(doubleValue(row, "llmRequestReduction")))
                .append(" | ").append(fmt(doubleValue(row, "payloadRatio")))
                .append(" | ").append(fmt(doubleValue(row, "positivePayloadRatio")))
                .append(" | ").append(fmt(doubleValue(row, "negativePayloadRatio")))
                .append(" | ").append(fmt(doubleValue(row, "positiveMeanSelectedChunks")))
                .append(" |\n");
    }

    private static Map<String, Object> findAblationConfig(
            List<Map<String, Object>> rows,
            String strategy,
            int chunkSize,
            int topK,
            double threshold
    ) {
        return rows.stream()
                .filter(row -> strategy.equals(row.get("strategy")))
                .filter(row -> intValue(row, "chunkSize") == chunkSize)
                .filter(row -> intValue(row, "topK") == topK)
                .filter(row -> Double.compare(doubleValue(row, "threshold"), threshold) == 0)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("missing ablation config " + strategy));
    }

    private static int scaledOverlap(int chunkSize) {
        return Math.max(0, (int) Math.round(chunkSize * (120.0 / 900.0)));
    }

    private static int utf8Bytes(String text) {
        return text == null ? 0 : text.getBytes(StandardCharsets.UTF_8).length;
    }

    private static int intValue(Map<String, Object> row, String key) {
        return ((Number) row.get(key)).intValue();
    }

    private static double doubleValue(Map<String, Object> row, String key) {
        return ((Number) row.get(key)).doubleValue();
    }

    private static double ratio(long numerator, long denominator) {
        return denominator == 0 ? 0.0 : (double) numerator / denominator;
    }

    private static String percent(double ratio) {
        return String.format(Locale.ROOT, "%.1f%%", ratio * 100.0);
    }

    private static Path resolveAblationDir(Path sweepDir) {
        String override = System.getenv("RAG_ABLATION_DIR");
        if (override != null && !override.isBlank()) {
            return Path.of(override).toAbsolutePath().normalize();
        }
        return sweepDir.getParent().resolve("rag-ablation").toAbsolutePath().normalize();
    }

    private static void deleteRecursively(Path root) {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (var paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (Exception ignored) {
                    // Temporary experiment index cleanup is best effort.
                }
            });
        } catch (Exception ignored) {
            // Temporary experiment index cleanup is best effort.
        }
    }

    private static final List<String> PAIR_CSV_COLUMNS = List.of(
            "pairId", "type", "shouldMatch", "resumeId", "jobId", "threshold",
            "chunkCount", "goldRelevant", "returned", "pass", "kept", "overTopk",
            "tp", "fp", "fn", "tn", "precision", "recall", "f1",
            "keptGold", "keptPrecision", "predictedMatch",
            "maxRaw", "maxBoosted", "minPassedRaw", "boostWouldCross",
            "elapsedSeconds"
    );

    private static final List<String> CHUNK_CSV_COLUMNS = List.of(
            "pairId", "type", "threshold", "chunkIndex", "section",
            "rawSimilarity", "boostedSimilarity", "kept", "status",
            "boostKeywords", "goldRelevant"
    );

    private static final List<String> THRESHOLD_CSV_COLUMNS = List.of(
            "threshold",
            "chunkPrecision", "chunkRecall", "chunkF1",
            "tp", "fp", "fn", "tn",
            "pairPrecision", "pairRecall", "pairF1",
            "pairTp", "pairFp", "pairFn", "pairTn",
            "positivePairsWithKept", "positivePairs", "positiveMeanKept", "positiveMeanPass",
            "positiveGoldRecall", "positiveKeptPrecision",
            "negativeFalsePassChunks", "negativePairsWithKept", "negativePairs",
            "hardNegFalsePassChunks", "hardNegPairsWithKept", "hardNegPairs",
            "boostWouldCrossChunks"
    );

    private static Map<String, Object> aggregate(
            List<Map<String, Object>> pairRows,
            List<PairSpec> pairs,
            double threshold
    ) {
        List<Map<String, Object>> rows = pairRows.stream()
                .filter(row -> Double.compare(((Number) row.get("threshold")).doubleValue(), threshold) == 0)
                .toList();

        int tp = sumInt(rows, "tp");
        int fp = sumInt(rows, "fp");
        int fn = sumInt(rows, "fn");
        int tn = sumInt(rows, "tn");
        Rates chunk = rates(tp, fp, fn);

        int pairTp = 0;
        int pairFp = 0;
        int pairFn = 0;
        int pairTn = 0;
        int positivePairs = 0;
        int positiveWithKept = 0;
        int positiveKeptSum = 0;
        int positivePassSum = 0;
        int positiveGold = 0;
        int positiveGoldTp = 0;
        int positiveKept = 0;
        int positiveKeptGold = 0;
        int negativePairs = 0;
        int negativeWithKept = 0;
        int negativeFp = 0;
        int hardPairs = 0;
        int hardWithKept = 0;
        int hardFp = 0;
        int boostWouldCross = sumInt(rows, "boostWouldCross");

        for (Map<String, Object> row : rows) {
            String type = String.valueOf(row.get("type"));
            boolean shouldMatch = (boolean) row.get("shouldMatch");
            boolean predicted = (boolean) row.get("predictedMatch");
            if (shouldMatch && predicted) {
                pairTp += 1;
            } else if (!shouldMatch && predicted) {
                pairFp += 1;
            } else if (shouldMatch) {
                pairFn += 1;
            } else {
                pairTn += 1;
            }
            if ("positive".equals(type)) {
                positivePairs += 1;
                positiveKeptSum += (int) row.get("kept");
                positivePassSum += (int) row.get("pass");
                positiveGold += (int) row.get("goldRelevant");
                positiveGoldTp += (int) row.get("tp");
                positiveKept += (int) row.get("kept");
                positiveKeptGold += (int) row.get("keptGold");
                if (predicted) {
                    positiveWithKept += 1;
                }
            } else if ("negative".equals(type)) {
                negativePairs += 1;
                negativeFp += (int) row.get("fp");
                if (predicted) {
                    negativeWithKept += 1;
                }
            } else if ("hard_negative".equals(type)) {
                hardPairs += 1;
                hardFp += (int) row.get("fp");
                if (predicted) {
                    hardWithKept += 1;
                }
            }
        }

        Rates pairRates = rates(pairTp, pairFp, pairFn);
        Rates positiveRecall = rates(positiveGoldTp, 0, positiveGold - positiveGoldTp);
        double keptPrecision = positiveKept == 0 ? 0.0 : (double) positiveKeptGold / positiveKept;

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("threshold", threshold);
        out.put("chunkPrecision", chunk.precision);
        out.put("chunkRecall", chunk.recall);
        out.put("chunkF1", chunk.f1);
        out.put("tp", tp);
        out.put("fp", fp);
        out.put("fn", fn);
        out.put("tn", tn);
        out.put("pairPrecision", pairRates.precision);
        out.put("pairRecall", pairRates.recall);
        out.put("pairF1", pairRates.f1);
        out.put("pairTp", pairTp);
        out.put("pairFp", pairFp);
        out.put("pairFn", pairFn);
        out.put("pairTn", pairTn);
        out.put("positivePairsWithKept", positiveWithKept);
        out.put("positivePairs", positivePairs);
        out.put("positiveMeanKept", positivePairs == 0 ? 0.0 : (double) positiveKeptSum / positivePairs);
        out.put("positiveMeanPass", positivePairs == 0 ? 0.0 : (double) positivePassSum / positivePairs);
        out.put("positiveGoldRecall", positiveRecall.recall);
        out.put("positiveKeptPrecision", keptPrecision);
        out.put("negativeFalsePassChunks", negativeFp);
        out.put("negativePairsWithKept", negativeWithKept);
        out.put("negativePairs", negativePairs);
        out.put("hardNegFalsePassChunks", hardFp);
        out.put("hardNegPairsWithKept", hardWithKept);
        out.put("hardNegPairs", hardPairs);
        out.put("boostWouldCrossChunks", boostWouldCross);
        return out;
    }

    private static PairThresholdMetrics scorePair(
            PairSpec pair,
            List<LabeledChunk> labeled,
            List<RetrievedChunk> retrieved,
            double threshold
    ) {
        Map<Integer, RetrievedChunk> byIndex = new LinkedHashMap<>();
        for (RetrievedChunk chunk : retrieved) {
            byIndex.put(chunk.chunkIndex(), chunk);
        }

        PairThresholdMetrics metrics = new PairThresholdMetrics();
        metrics.chunkCount = labeled.size();
        metrics.returned = retrieved.size();
        metrics.goldRelevant = (int) labeled.stream().filter(LabeledChunk::relevant).count();
        metrics.maxRaw = retrieved.stream().mapToDouble(RetrievedChunk::rawSimilarity).max().orElse(0.0);
        metrics.maxBoosted = retrieved.stream().mapToDouble(RetrievedChunk::similarity).max().orElse(0.0);
        metrics.minPassedRaw = Double.NaN;

        for (LabeledChunk gold : labeled) {
            RetrievedChunk hit = byIndex.get(gold.index());
            boolean pass = hit != null && hit.rawSimilarity() >= threshold;
            boolean kept = hit != null && hit.kept();
            if (hit != null && "over-topk".equals(hit.status())) {
                metrics.overTopk += 1;
            }
            if (pass) {
                metrics.pass += 1;
                if (Double.isNaN(metrics.minPassedRaw) || hit.rawSimilarity() < metrics.minPassedRaw) {
                    metrics.minPassedRaw = hit.rawSimilarity();
                }
            }
            if (kept) {
                metrics.kept += 1;
                if (gold.relevant()) {
                    metrics.keptGold += 1;
                }
            }
            if (gold.relevant() && pass) {
                metrics.tp += 1;
            } else if (!gold.relevant() && pass) {
                metrics.fp += 1;
            } else if (gold.relevant()) {
                metrics.fn += 1;
            } else {
                metrics.tn += 1;
            }
            if (hit != null && hit.rawSimilarity() < threshold && hit.similarity() >= threshold) {
                metrics.boostWouldCross += 1;
            }
        }

        Rates rates = rates(metrics.tp, metrics.fp, metrics.fn);
        metrics.precision = rates.precision;
        metrics.recall = rates.recall;
        metrics.f1 = rates.f1;
        metrics.keptPrecision = metrics.kept == 0 ? 0.0 : (double) metrics.keptGold / metrics.kept;
        metrics.predictedMatch = metrics.kept > 0;
        return metrics;
    }

    private static Map<String, Object> retrievedToMap(RetrievedChunk chunk) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("chunkIndex", chunk.chunkIndex());
        map.put("rawSimilarity", chunk.rawSimilarity());
        map.put("boostedSimilarity", chunk.similarity());
        map.put("kept", chunk.kept());
        map.put("status", chunk.status());
        map.put("section", chunk.section());
        map.put("boostKeywords", chunk.boostKeywords());
        map.put("content", chunk.content());
        return map;
    }

    private static Map<String, Object> buildChunkPreview(
            Path sweepDir,
            DatasetFile dataset,
            TextChunker chunker
    ) throws Exception {
        Map<String, ResumeSpec> resumes = new LinkedHashMap<>();
        dataset.resumes().forEach(item -> resumes.put(item.id(), item));
        Map<String, JobSpec> jobs = new LinkedHashMap<>();
        dataset.jobs().forEach(item -> jobs.put(item.id(), item));

        Map<String, List<Map<String, Object>>> resumeChunks = new LinkedHashMap<>();
        for (ResumeSpec spec : dataset.resumes()) {
            String text = Files.readString(sweepDir.resolve("dataset").resolve(spec.file()), StandardCharsets.UTF_8);
            List<String> chunks = chunker.split(text);
            List<Map<String, Object>> rendered = new ArrayList<>();
            for (int i = 0; i < chunks.size(); i++) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("index", i);
                row.put("section", TextChunker.detectSection(chunks.get(i)));
                row.put("chars", chunks.get(i).length());
                row.put("preview", preview(chunks.get(i)));
                row.put("content", chunks.get(i));
                rendered.add(row);
            }
            resumeChunks.put(spec.id(), rendered);
        }

        List<Map<String, Object>> pairLabels = new ArrayList<>();
        for (PairSpec pair : dataset.pairs()) {
            ResumeSpec resume = resumes.get(pair.resumeId());
            if (resume == null) {
                throw new IllegalStateException("unknown resumeId " + pair.resumeId());
            }
            List<Map<String, Object>> chunks = resumeChunks.get(pair.resumeId());
            List<Map<String, Object>> labeled = new ArrayList<>();
            int relevantCount = 0;
            for (Map<String, Object> chunk : chunks) {
                String content = String.valueOf(chunk.get("content"));
                List<String> hits = matchingPhrases(content, pair.goldPhrases());
                boolean relevant = !hits.isEmpty();
                if (relevant) {
                    relevantCount += 1;
                }
                if ("positive".equals(pair.type()) == false && relevant) {
                    throw new IllegalStateException(
                            "negative/hard-negative pair " + pair.id() + " must not have gold phrase hits"
                    );
                }
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("index", chunk.get("index"));
                row.put("section", chunk.get("section"));
                row.put("chars", chunk.get("chars"));
                row.put("relevant", relevant);
                row.put("goldHits", hits);
                row.put("preview", chunk.get("preview"));
                row.put("content", content);
                labeled.add(row);
            }
            if ("positive".equals(pair.type()) && relevantCount == 0) {
                throw new IllegalStateException("positive pair " + pair.id() + " has zero gold-relevant chunks");
            }
            Map<String, Object> pairRow = new LinkedHashMap<>();
            pairRow.put("pairId", pair.id());
            pairRow.put("type", pair.type());
            pairRow.put("resumeId", pair.resumeId());
            pairRow.put("jobId", pair.jobId());
            pairRow.put("shouldMatch", pair.shouldMatch());
            pairRow.put("notes", pair.notes());
            pairRow.put("goldPhrases", pair.goldPhrases());
            pairRow.put("relevantCount", relevantCount);
            pairRow.put("chunks", labeled);
            pairLabels.add(pairRow);
        }

        Map<String, Object> preview = new LinkedHashMap<>();
        preview.put("generatedAt", Instant.now().toString());
        preview.put("chunkSize", 900);
        preview.put("chunkOverlap", 120);
        preview.put("resumeChunks", resumeChunks);
        preview.put("pairs", pairLabels);
        return preview;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, List<LabeledChunk>> labeledChunksByPair(Map<String, Object> preview) {
        Map<String, List<LabeledChunk>> out = new LinkedHashMap<>();
        List<Map<String, Object>> pairs = (List<Map<String, Object>>) preview.get("pairs");
        for (Map<String, Object> pair : pairs) {
            String pairId = String.valueOf(pair.get("pairId"));
            List<Map<String, Object>> chunks = (List<Map<String, Object>>) pair.get("chunks");
            List<LabeledChunk> labeled = new ArrayList<>();
            for (Map<String, Object> chunk : chunks) {
                labeled.add(new LabeledChunk(
                        ((Number) chunk.get("index")).intValue(),
                        Boolean.TRUE.equals(chunk.get("relevant")),
                        String.valueOf(chunk.get("content"))
                ));
            }
            out.put(pairId, labeled);
        }
        return out;
    }

    private static Map<String, Resume> materializeResumes(Path sweepDir, DatasetFile dataset, AppUser user) throws Exception {
        Map<String, Resume> out = new LinkedHashMap<>();
        long id = 10_000L;
        for (ResumeSpec spec : dataset.resumes()) {
            Resume resume = new Resume();
            setId(resume, id++);
            resume.setUser(user);
            resume.setTitle(spec.title());
            resume.setCandidateName(spec.candidateName());
            resume.setRawText(Files.readString(sweepDir.resolve("dataset").resolve(spec.file()), StandardCharsets.UTF_8));
            out.put(spec.id(), resume);
        }
        return out;
    }

    private static Map<String, JobDescription> materializeJobs(Path sweepDir, DatasetFile dataset, AppUser user) throws Exception {
        Map<String, JobDescription> out = new LinkedHashMap<>();
        long id = 20_000L;
        for (JobSpec spec : dataset.jobs()) {
            String text = Files.readString(sweepDir.resolve("dataset").resolve(spec.file()), StandardCharsets.UTF_8);
            JobDescription job = new JobDescription();
            setId(job, id++);
            job.setUser(user);
            job.setTitle(spec.title());
            job.setCompanyName(spec.companyName());
            job.setLocation(spec.location());
            job.setEmploymentType(spec.employmentType());
            int reqAt = indexOfRequirementHeading(text);
            if (reqAt >= 0) {
                job.setDescription(text.substring(0, reqAt).trim());
                job.setRequirements(text.substring(reqAt).trim());
            } else {
                job.setDescription(text.trim());
                job.setRequirements("");
            }
            out.put(spec.id(), job);
        }
        return out;
    }

    private static int indexOfRequirementHeading(String text) {
        int idx = text.indexOf("任职要求");
        return idx;
    }

    private static AppUser experimentUser() throws Exception {
        AppUser user = new AppUser();
        setId(user, 1L);
        user.setUsername("threshold-sweep");
        user.setEmail("threshold-sweep@example.com");
        user.setDisplayName("threshold-sweep");
        user.setPasswordHash("not-used");
        return user;
    }

    private static DatasetFile loadDataset(Path sweepDir, ObjectMapper mapper) throws Exception {
        Path pairs = sweepDir.resolve("dataset").resolve("pairs.json");
        if (!Files.isRegularFile(pairs)) {
            throw new IllegalStateException("missing dataset: " + pairs.toAbsolutePath());
        }
        return mapper.readValue(pairs.toFile(), DatasetFile.class);
    }

    private static RagProperties productionProperties() {
        RagProperties properties = new RagProperties();
        properties.setChunkSize(900);
        properties.setChunkOverlap(120);
        properties.setTopK(5);
        // 与生产默认值保持一致；扫描时每个阈值都会被 setMinSimilarity 覆盖，
        // 这里的取值只影响分块预览等不扫描阈值的路径。
        properties.setMinSimilarity(0.72);
        properties.setHybridEnabled(true);
        properties.setKeywordBoost(0.035);
        properties.setMaxKeywordBoosts(3);
        properties.setDualQueryEnabled(true);
        return properties;
    }

    private static List<Double> sweepThresholds() {
        List<Double> thresholds = new ArrayList<>();
        for (int value = 35; value <= 80; value += 5) {
            thresholds.add(value / 100.0);
            // 块级边界校准点。本数据集上可分离区间是 (0.690011, 0.751004)——
            // 下界是最高的非金标难负块 H1 chunk0，上界是最低的金标相关块 P2 chunk1，
            // 宽度仅 0.061，而 0.05 的网格正好跨过它，两端的 0.70 与 0.75 都紧贴边界。
            // 0.72 取该区间中点（0.720508）附近，需要单独测量。
            if (value == 70) {
                thresholds.add(0.72);
            }
        }
        return thresholds;
    }

    @SuppressWarnings("unchecked")
    private static ResumeChunkRepository inMemoryRepository(Map<Long, List<ResumeChunk>> store) {
        return (ResumeChunkRepository) Proxy.newProxyInstance(
                ResumeChunkRepository.class.getClassLoader(),
                new Class<?>[]{ResumeChunkRepository.class},
                (proxy, method, args) -> {
                    return switch (method.getName()) {
                        case "findByResumeIdOrderByChunkIndexAsc" -> {
                            Long resumeId = (Long) args[0];
                            yield List.copyOf(store.getOrDefault(resumeId, List.of()));
                        }
                        case "deleteByResumeId" -> {
                            Long resumeId = (Long) args[0];
                            List<ResumeChunk> removed = store.remove(resumeId);
                            yield removed == null ? 0L : (long) removed.size();
                        }
                        case "flush" -> null;
                        case "saveAllAndFlush" -> {
                            List<ResumeChunk> chunks = (List<ResumeChunk>) args[0];
                            if (chunks == null || chunks.isEmpty()) {
                                yield List.of();
                            }
                            Long resumeId = chunks.getFirst().getResume().getId();
                            List<ResumeChunk> copy = new ArrayList<>(chunks);
                            copy.sort(Comparator.comparingInt(ResumeChunk::getChunkIndex));
                            store.put(resumeId, copy);
                            yield copy;
                        }
                        case "equals" -> proxy == args[0];
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "toString" -> "InMemoryResumeChunkRepository";
                        default -> throw new UnsupportedOperationException(
                                "in-memory ResumeChunkRepository does not implement " + method.getName()
                        );
                    };
                }
        );
    }

    private static Path resolveSweepDir() {
        String override = System.getenv("THRESHOLD_SWEEP_DIR");
        if (override != null && !override.isBlank()) {
            return Path.of(override).toAbsolutePath().normalize();
        }
        Path cwd = Path.of("").toAbsolutePath().normalize();
        Path nested = cwd.resolve("experiments").resolve("threshold-sweep");
        if (Files.isRegularFile(nested.resolve("dataset").resolve("pairs.json"))) {
            return nested;
        }
        Path sibling = cwd.getParent() == null
                ? nested
                : cwd.getParent().resolve("experiments").resolve("threshold-sweep");
        return sibling;
    }

    private static String resolveResource(String environmentVariable, Path localPath, String remoteUri) {
        String configured = System.getenv(environmentVariable);
        if (configured != null && !configured.isBlank()) {
            return configured;
        }
        Path absoluteLocalPath = localPath.toAbsolutePath().normalize();
        return Files.isRegularFile(absoluteLocalPath) ? absoluteLocalPath.toUri().toString() : remoteUri;
    }

    private static void assertLocalModelPresent(String tokenizerUri, String modelUri, PrintStream log) {
        Path tokenizer = uriToExistingFile(tokenizerUri);
        Path model = uriToExistingFile(modelUri);
        if (tokenizer == null || model == null) {
            throw new IllegalStateException(
                    "ONNX assets are not local files; refusing to silently download during the sweep. "
                            + "tokenizer=" + tokenizerUri + " model=" + modelUri
            );
        }
        log("local tokenizer bytes=" + tokenizer.toFile().length(), log);
        log("local model bytes=" + model.toFile().length(), log);
        if (model.toFile().length() < 100_000_000L) {
            throw new IllegalStateException("ONNX model file looks truncated: " + model + " bytes=" + model.toFile().length());
        }
    }

    private static Path uriToExistingFile(String uri) {
        if (uri == null) {
            return null;
        }
        try {
            Path path;
            if (uri.startsWith("file:")) {
                path = Path.of(java.net.URI.create(uri));
            } else {
                path = Path.of(uri);
            }
            return Files.isRegularFile(path) ? path : null;
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private static void setId(Object entity, Long id) throws Exception {
        Field field = entity.getClass().getDeclaredField("id");
        field.setAccessible(true);
        field.set(entity, id);
    }

    private static List<String> matchingPhrases(String content, List<String> phrases) {
        if (phrases == null || phrases.isEmpty() || content == null) {
            return List.of();
        }
        String haystack = content.toLowerCase(Locale.ROOT);
        List<String> hits = new ArrayList<>();
        for (String phrase : phrases) {
            if (phrase != null && !phrase.isBlank() && haystack.contains(phrase.toLowerCase(Locale.ROOT))) {
                hits.add(phrase);
            }
        }
        return hits;
    }

    private static String preview(String text) {
        String normalized = text.replace('\n', ' ').trim();
        return normalized.length() <= 80 ? normalized : normalized.substring(0, 80) + "...";
    }

    private static ObjectMapper mapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.enable(SerializationFeature.INDENT_OUTPUT);
        return mapper;
    }

    private static void log(String message, PrintStream log) {
        System.out.println(message);
        log.println(message);
    }

    private static double elapsedSeconds(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000_000.0;
    }

    private static String formatThreshold(double threshold) {
        return String.format(Locale.ROOT, "%.2f", threshold);
    }

    private static int sumInt(List<Map<String, Object>> rows, String key) {
        int sum = 0;
        for (Map<String, Object> row : rows) {
            sum += ((Number) row.get(key)).intValue();
        }
        return sum;
    }

    private static Rates rates(int tp, int fp, int fn) {
        double precision = (tp + fp) == 0 ? 0.0 : (double) tp / (tp + fp);
        double recall = (tp + fn) == 0 ? 0.0 : (double) tp / (tp + fn);
        double f1 = (precision + recall) == 0.0 ? 0.0 : 2.0 * precision * recall / (precision + recall);
        return new Rates(precision, recall, f1);
    }

    private static void writeCsv(Path path, List<Map<String, Object>> rows, List<String> columns) throws Exception {
        StringBuilder csv = new StringBuilder();
        csv.append(String.join(",", columns)).append('\n');
        for (Map<String, Object> row : rows) {
            List<String> values = new ArrayList<>();
            for (String column : columns) {
                values.add(csvEscape(row.get(column)));
            }
            csv.append(String.join(",", values)).append('\n');
        }
        Files.writeString(path, csv.toString(), StandardCharsets.UTF_8);
    }

    private static String csvEscape(Object value) {
        if (value == null) {
            return "";
        }
        String text = value instanceof Double || value instanceof Float
                ? String.format(Locale.ROOT, "%.6f", ((Number) value).doubleValue())
                : String.valueOf(value);
        if (text.contains(",") || text.contains("\"") || text.contains("\n")) {
            return "\"" + text.replace("\"", "\"\"") + "\"";
        }
        return text;
    }

    private static String renderAutoReport(
            List<Map<String, Object>> thresholds,
            List<Map<String, Object>> pairRows,
            DatasetFile dataset
    ) {
        StringBuilder md = new StringBuilder();
        md.append("# 阈值扫描自动表（程序生成，数字来自本次 retrieve）\n\n");
        md.append("生成时间：").append(Instant.now()).append("\n\n");
        md.append("配对 ").append(dataset.pairs().size()).append(" 组，阈值 0.35–0.80 步长 0.05。\n\n");
        md.append("## 汇总\n\n");
        md.append("| 阈值 | 块P | 块R | 块F1 | 配对P | 配对R | 配对F1 | 正样本有证据 | 负样本误放行对数 | 难负误放行对数 | 负样本误过阈块 | 难负误过阈块 | boost本可越阈 |\n");
        md.append("|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|\n");
        for (Map<String, Object> row : thresholds) {
            md.append("| ").append(formatThreshold((double) row.get("threshold")))
                    .append(" | ").append(fmt((double) row.get("chunkPrecision")))
                    .append(" | ").append(fmt((double) row.get("chunkRecall")))
                    .append(" | ").append(fmt((double) row.get("chunkF1")))
                    .append(" | ").append(fmt((double) row.get("pairPrecision")))
                    .append(" | ").append(fmt((double) row.get("pairRecall")))
                    .append(" | ").append(fmt((double) row.get("pairF1")))
                    .append(" | ").append(row.get("positivePairsWithKept")).append("/").append(row.get("positivePairs"))
                    .append(" | ").append(row.get("negativePairsWithKept"))
                    .append(" | ").append(row.get("hardNegPairsWithKept"))
                    .append(" | ").append(row.get("negativeFalsePassChunks"))
                    .append(" | ").append(row.get("hardNegFalsePassChunks"))
                    .append(" | ").append(row.get("boostWouldCrossChunks"))
                    .append(" |\n");
        }
        md.append("\n## 各阈值正样本均kept / 均pass\n\n");
        md.append("| 阈值 | 正样本均kept | 正样本均pass | 正样本gold召回 | 正样本kept精确率 |\n");
        md.append("|---:|---:|---:|---:|---:|\n");
        for (Map<String, Object> row : thresholds) {
            md.append("| ").append(formatThreshold((double) row.get("threshold")))
                    .append(" | ").append(fmt((double) row.get("positiveMeanKept")))
                    .append(" | ").append(fmt((double) row.get("positiveMeanPass")))
                    .append(" | ").append(fmt((double) row.get("positiveGoldRecall")))
                    .append(" | ").append(fmt((double) row.get("positiveKeptPrecision")))
                    .append(" |\n");
        }
        md.append("\n本文件由测试类生成，解读见 `RESULTS.md`。\n");
        return md.toString();
    }

    private static String fmt(double value) {
        return String.format(Locale.ROOT, "%.3f", value);
    }

    private record Rates(double precision, double recall, double f1) {
    }

    private record LabeledChunk(int index, boolean relevant, String content) {
    }

    private static final class PairThresholdMetrics {
        int chunkCount;
        int goldRelevant;
        int returned;
        int pass;
        int kept;
        int overTopk;
        int tp;
        int fp;
        int fn;
        int tn;
        int keptGold;
        int boostWouldCross;
        double precision;
        double recall;
        double f1;
        double keptPrecision;
        boolean predictedMatch;
        double maxRaw;
        double maxBoosted;
        double minPassedRaw;

        Map<String, Object> toRow(PairSpec pair, Long numericResumeId, double elapsedSeconds, double threshold) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("pairId", pair.id());
            row.put("type", pair.type());
            row.put("shouldMatch", pair.shouldMatch());
            row.put("resumeId", pair.resumeId());
            row.put("jobId", pair.jobId());
            row.put("numericResumeId", numericResumeId);
            row.put("threshold", threshold);
            row.put("chunkCount", chunkCount);
            row.put("goldRelevant", goldRelevant);
            row.put("returned", returned);
            row.put("pass", pass);
            row.put("kept", kept);
            row.put("overTopk", overTopk);
            row.put("tp", tp);
            row.put("fp", fp);
            row.put("fn", fn);
            row.put("tn", tn);
            row.put("precision", precision);
            row.put("recall", recall);
            row.put("f1", f1);
            row.put("keptGold", keptGold);
            row.put("keptPrecision", keptPrecision);
            row.put("predictedMatch", predictedMatch);
            row.put("maxRaw", maxRaw);
            row.put("maxBoosted", maxBoosted);
            row.put("minPassedRaw", Double.isNaN(minPassedRaw) ? "" : minPassedRaw);
            row.put("boostWouldCross", boostWouldCross);
            row.put("elapsedSeconds", elapsedSeconds);
            return row;
        }
    }

    // Fix: the call site uses toRow(pair, id, seconds) — update call to pass threshold.
    // Kept a clean overload below for the actual call after I patch the call site.

    @JsonIgnoreProperties(ignoreUnknown = true)
    record DatasetFile(int version, String notes, List<ResumeSpec> resumes, List<JobSpec> jobs, List<PairSpec> pairs) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ResumeSpec(String id, String file, String title, String candidateName) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record JobSpec(
            String id,
            String file,
            String title,
            String companyName,
            String location,
            String employmentType
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record PairSpec(
            String id,
            String type,
            String resumeId,
            String jobId,
            boolean shouldMatch,
            List<String> goldPhrases,
            String notes
    ) {
        PairSpec {
            if (goldPhrases == null) {
                goldPhrases = List.of();
            }
        }
    }
}
