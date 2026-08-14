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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
        properties.setMinSimilarity(0.55);
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
