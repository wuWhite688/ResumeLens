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
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
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
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * One-shot holdout evaluation against the exact committed production RAG defaults.
 *
 * <p>This runner intentionally delegates scoring/aggregation to the legacy ablation helpers so
 * holdout numbers stay directly comparable with experiments/rag-ablation. The old helpers are
 * private, therefore this test-only adapter uses reflection rather than copying their metric math.
 */
class HoldoutExperimentTests {
    private static final List<String> PAIR_TYPES = List.of("positive", "negative", "hard_negative");
    private static final List<String> DOMAIN_RELATIONS = List.of(
            HoldoutRunnerSupport.SEEN_DOMAIN,
            HoldoutRunnerSupport.NEW_DOMAIN
    );
    private static final List<String> STRATEGIES = List.of("full_text", "rag");

    private static final List<String> PAIR_COLUMNS = List.of(
            "strategy", "pairId", "type", "domainRelation", "shouldMatch", "resumeId", "jobId",
            "chunkSize", "chunkOverlap", "topK", "threshold",
            "chunkCount", "goldRelevant", "selectedChunks", "tp", "fp", "fn",
            "chunkPrecision", "chunkRecall", "chunkF1", "predictedEvidenceGate", "pairCorrect",
            "evidenceChars", "evidenceUtf8Bytes", "fullTextChars", "fullTextUtf8Bytes"
    );

    private static final List<String> CONFIG_COLUMNS = List.of(
            "sliceDimension", "sliceValue", "strategy", "sampleCount", "productionConfig",
            "chunkSize", "chunkOverlap", "topK", "threshold", "pairs",
            "chunkPrecision", "chunkRecall", "chunkF1",
            "pairPrecision", "pairRecall", "pairF1", "pairAccuracy",
            "pairTp", "pairFp", "pairFn", "pairTn",
            "llmRequestsTriggered", "llmRequestReduction",
            "meanSelectedChunks", "positiveMeanSelectedChunks", "negativeMeanSelectedChunks",
            "payloadChars", "fullTextChars", "payloadRatio", "payloadReduction",
            "positivePayloadRatio", "negativePayloadRatio"
    );

    private static final Class<?> LEGACY_LABELED_CHUNK = nestedClass("LabeledChunk");
    private static final Constructor<?> LEGACY_LABELED_CHUNK_CTOR = privateConstructor(
            LEGACY_LABELED_CHUNK, int.class, boolean.class, String.class
    );
    private static final Method LEGACY_MATCHING_PHRASES = privateMethod(
            "matchingPhrases", String.class, List.class
    );
    private static final Method LEGACY_SCORE_ABLATION_PAIR = privateMethod(
            "scoreAblationPair",
            String.class,
            ThresholdSweepExperimentTests.PairSpec.class,
            List.class,
            List.class,
            int.class,
            int.class,
            int.class,
            double.class,
            int.class,
            int.class,
            int.class,
            int.class
    );
    private static final Method LEGACY_AGGREGATE_ABLATION_ROWS = privateMethod(
            "aggregateAblationRows",
            List.class,
            String.class,
            int.class,
            int.class,
            int.class,
            double.class
    );
    private static final Method LEGACY_IN_MEMORY_REPOSITORY = privateMethod(
            "inMemoryRepository", Map.class
    );

    @Test
    @EnabledIfEnvironmentVariable(named = "RUN_HOLDOUT", matches = "true")
    @Timeout(value = 40, unit = TimeUnit.MINUTES)
    void evaluateFrozenHoldoutAtProductionConfig() throws Exception {
        Path repoRoot = resolveRepoRoot();
        Path holdoutRoot = repoRoot.resolve("experiments").resolve("holdout");
        Path runLog = holdoutRoot.resolve("RUN-LOG.md");
        Path datasetDir = resolveDatasetDir(repoRoot);
        ObjectMapper mapper = mapper();

        HoldoutRunnerSupport.RunPlan plan =
                HoldoutRunnerSupport.planRun(datasetDir, holdoutRoot, runLog, mapper);
        DatasetFile dataset = loadDataset(datasetDir, mapper);
        assertFalse(dataset.pairs().isEmpty(), "dataset pairs must not be empty");

        HoldoutRunnerSupport.ProductionConfig config = HoldoutRunnerSupport.loadProductionConfig();
        validateDataset(dataset);

        if (!plan.formal()) {
            deleteRecursively(plan.outputRoot());
        }
        Path outputRoot = plan.outputRoot();
        Path resultsDir = outputRoot.resolve("results");
        Path logsDir = outputRoot.resolve("logs");
        Files.createDirectories(resultsDir);
        Files.createDirectories(logsDir);

        String commitSha = resolveCommitSha(repoRoot);
        Path consoleLog = logsDir.resolve("holdout-console.log");

        if (plan.formal()) {
            HoldoutRunnerSupport.appendPendingRun(
                    runLog,
                    plan.holdoutVersion(),
                    config,
                    commitSha,
                    "experiments/holdout/RESULTS.md",
                    HoldoutRunnerSupport.runStartedNote()
            );
        }

        boolean completed = false;
        ClsOnnxEmbeddingModel embeddingModel = null;
        Path luceneDir = null;
        try (PrintStream log = new PrintStream(Files.newOutputStream(consoleLog), true, StandardCharsets.UTF_8)) {
            log("=== holdout start " + Instant.now() + " ===", log);
            log("mode=" + (plan.formal() ? "formal" : "smoke"), log);
            log("holdoutVersion=" + (plan.holdoutVersion() == null ? "(none)" : plan.holdoutVersion()), log);
            log("dataset=" + datasetDir.toAbsolutePath(), log);
            log("commit=" + commitSha, log);
            log("config=" + config, log);

            RagProperties properties = productionProperties(config);
            TextChunker chunker = new TextChunker(properties);
            String tokenizerUri = resolveModelResource(
                    "RAG_EMBEDDING_TOKENIZER_URI",
                    Path.of("models", "gte-multilingual-base-int8", "tokenizer.json"),
                    properties.getEmbeddingTokenizerUri()
            );
            String modelUri = resolveModelResource(
                    "RAG_EMBEDDING_MODEL_URI",
                    Path.of("models", "gte-multilingual-base-int8", "model_int8.onnx"),
                    properties.getEmbeddingModelUri()
            );
            assertLocalModelPresent(tokenizerUri, modelUri, log);

            luceneDir = Files.createTempDirectory("holdout-lucene");
            Map<Long, List<ResumeChunk>> chunkStore = new ConcurrentHashMap<>();
            ResumeChunkRepository repository = legacyInMemoryRepository(chunkStore);
            ResumeIndexStore indexStore = new ResumeIndexStore(repository);
            ObjectMapper embeddingMapper = new ObjectMapper();

            embeddingModel = new ClsOnnxEmbeddingModel(
                    tokenizerUri,
                    modelUri,
                    properties.getEmbeddingTokenizerSha256(),
                    properties.getEmbeddingModelSha256(),
                    properties.getModelOutputName(),
                    Map.of(
                            "padding", "true",
                            "truncation", "true",
                            "modelMaxLength", String.valueOf(properties.getMaxLength()),
                            "maxLength", String.valueOf(properties.getMaxLength())
                    ),
                    properties.getEmbeddingDimensions()
            );

            long loadStarted = System.nanoTime();
            embeddingModel.afterPropertiesSet();
            log(String.format(Locale.ROOT, "ONNX model loaded in %.1fs", elapsedSeconds(loadStarted)), log);

            List<Map<String, Object>> pairRows = new ArrayList<>();
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
                Map<String, Resume> resumes = materializeResumes(datasetDir, dataset, user);
                Map<String, JobDescription> jobs = materializeJobs(datasetDir, dataset, user);

                int ordinal = 0;
                for (PairSpec pair : dataset.pairs()) {
                    ordinal += 1;
                    Resume resume = requirePresent(resumes, pair.resumeId(), "resume", pair.id());
                    JobDescription job = requirePresent(jobs, pair.jobId(), "job", pair.id());
                    List<String> chunks = chunker.split(resume.getRawText());
                    List<Object> labeled = legacyLabels(pair, chunks);
                    ThresholdSweepExperimentTests.PairSpec legacyPair = legacyPair(pair);

                    long started = System.nanoTime();
                    List<RetrievedChunk> retrieved = service.retrieve(user, resume, job);
                    List<RetrievedChunk> selected = retrieved.stream()
                            .filter(RetrievedChunk::kept)
                            .toList();
                    double seconds = elapsedSeconds(started);

                    List<RetrievedChunk> fullText = allChunksAsSelected(chunks);
                    Map<String, Object> fullRow = legacyScore(
                            "full_text",
                            legacyPair,
                            labeled,
                            fullText,
                            config,
                            0,
                            0.0,
                            resume.getRawText()
                    );
                    decoratePairRow(fullRow, pair);
                    pairRows.add(fullRow);

                    String evidence = selected.stream()
                            .map(RetrievedChunk::content)
                            .collect(Collectors.joining("\n\n"));
                    Map<String, Object> ragRow = legacyScore(
                            "rag",
                            legacyPair,
                            labeled,
                            selected,
                            config,
                            config.topK(),
                            config.minSimilarity(),
                            evidence,
                            resume.getRawText()
                    );
                    decoratePairRow(ragRow, pair);
                    pairRows.add(ragRow);

                    log(String.format(
                            Locale.ROOT,
                            "[%d/%d] %s type=%s domain=%s selected=%d gold=%d %.2fs",
                            ordinal,
                            dataset.pairs().size(),
                            pair.id(),
                            pair.type(),
                            pair.domainRelation(),
                            selected.size(),
                            fullRow.get("goldRelevant"),
                            seconds
                    ), log);
                }
            }

            List<Map<String, Object>> configRows = aggregateSlices(pairRows, config);
            mapper.writerWithDefaultPrettyPrinter().writeValue(
                    resultsDir.resolve("pair-metrics.json").toFile(), pairRows
            );
            mapper.writerWithDefaultPrettyPrinter().writeValue(
                    resultsDir.resolve("config-metrics.json").toFile(), configRows
            );
            writeCsv(resultsDir.resolve("pair-metrics.csv"), pairRows, PAIR_COLUMNS);
            writeCsv(resultsDir.resolve("config-metrics.csv"), configRows, CONFIG_COLUMNS);

            String report = renderReport(
                    dataset,
                    pairRows,
                    configRows,
                    config,
                    plan,
                    datasetDir,
                    commitSha
            );
            Files.writeString(outputRoot.resolve("RESULTS.md"), report, StandardCharsets.UTF_8);
            completed = true;
            log("wrote " + outputRoot.resolve("RESULTS.md").toAbsolutePath(), log);
            log("=== holdout end " + Instant.now() + " completed=true ===", log);
        } catch (Exception ex) {
            Files.writeString(
                    outputRoot.resolve("FAILURE.txt"),
                    ex.getClass().getName() + ": " + ex.getMessage() + System.lineSeparator(),
                    StandardCharsets.UTF_8
            );
            throw ex;
        } finally {
            if (embeddingModel != null) {
                embeddingModel.destroy();
            }
            if (luceneDir != null) {
                deleteRecursively(luceneDir);
            }
        }

        assertTrue(completed, "holdout run must complete");
        assertTrue(Files.size(resultsDir.resolve("config-metrics.csv")) > 0);
        assertTrue(Files.size(outputRoot.resolve("RESULTS.md")) > 0);
    }

    private static RagProperties productionProperties(HoldoutRunnerSupport.ProductionConfig config) {
        RagProperties properties = new RagProperties();
        properties.setChunkSize(config.chunkSize());
        properties.setChunkOverlap(config.chunkOverlap());
        properties.setTopK(config.topK());
        properties.setMinSimilarity(config.minSimilarity());
        return properties;
    }

    private static void validateDataset(DatasetFile dataset) {
        if (dataset.resumes() == null || dataset.jobs() == null || dataset.pairs() == null) {
            throw new IllegalStateException("pairs.json must contain resumes, jobs and pairs arrays");
        }
        for (PairSpec pair : dataset.pairs()) {
            if (!PAIR_TYPES.contains(pair.type())) {
                throw new IllegalStateException("unsupported pair type " + pair.type() + " for " + pair.id());
            }
            HoldoutRunnerSupport.normalizeDomainRelation(pair.domainRelation());
            if ("positive".equals(pair.type()) != pair.shouldMatch()) {
                throw new IllegalStateException(
                        "pair " + pair.id() + " shouldMatch must be true only for positive pairs"
                );
            }
            if (!"positive".equals(pair.type()) && !pair.goldPhrases().isEmpty()) {
                throw new IllegalStateException(
                        "negative/hard-negative pair " + pair.id() + " must have empty goldPhrases"
                );
            }
        }
    }

    private static List<Object> legacyLabels(PairSpec pair, List<String> chunks) throws Exception {
        List<Object> labeled = new ArrayList<>();
        int relevant = 0;
        for (int index = 0; index < chunks.size(); index++) {
            String content = chunks.get(index);
            @SuppressWarnings("unchecked")
            List<String> hits = (List<String>) LEGACY_MATCHING_PHRASES.invoke(null, content, pair.goldPhrases());
            boolean isRelevant = !hits.isEmpty();
            if (isRelevant) {
                relevant += 1;
            }
            if (!"positive".equals(pair.type()) && isRelevant) {
                throw new IllegalStateException(
                        "negative/hard-negative pair " + pair.id() + " must not have gold phrase hits"
                );
            }
            labeled.add(LEGACY_LABELED_CHUNK_CTOR.newInstance(index, isRelevant, content));
        }
        if ("positive".equals(pair.type()) && relevant == 0) {
            throw new IllegalStateException("positive pair " + pair.id() + " has zero gold-relevant chunks");
        }
        return labeled;
    }

    private static ThresholdSweepExperimentTests.PairSpec legacyPair(PairSpec pair) {
        return new ThresholdSweepExperimentTests.PairSpec(
                pair.id(),
                pair.type(),
                pair.resumeId(),
                pair.jobId(),
                pair.shouldMatch(),
                pair.goldPhrases(),
                pair.notes()
        );
    }

    private static List<RetrievedChunk> allChunksAsSelected(List<String> chunks) {
        List<RetrievedChunk> selected = new ArrayList<>();
        for (int index = 0; index < chunks.size(); index++) {
            String content = chunks.get(index);
            selected.add(new RetrievedChunk(
                    index,
                    content,
                    1.0,
                    1.0,
                    true,
                    "kept",
                    TextChunker.detectSection(content),
                    List.of()
            ));
        }
        return selected;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> legacyScore(
            String strategy,
            ThresholdSweepExperimentTests.PairSpec pair,
            List<Object> labeled,
            List<RetrievedChunk> selected,
            HoldoutRunnerSupport.ProductionConfig config,
            int topK,
            double threshold,
            String evidence,
            String fullText
    ) throws Exception {
        return (Map<String, Object>) LEGACY_SCORE_ABLATION_PAIR.invoke(
                null,
                strategy,
                pair,
                labeled,
                selected,
                config.chunkSize(),
                config.chunkOverlap(),
                topK,
                threshold,
                evidence.length(),
                utf8Bytes(evidence),
                fullText.length(),
                utf8Bytes(fullText)
        );
    }

    private static Map<String, Object> legacyScore(
            String strategy,
            ThresholdSweepExperimentTests.PairSpec pair,
            List<Object> labeled,
            List<RetrievedChunk> selected,
            HoldoutRunnerSupport.ProductionConfig config,
            int topK,
            double threshold,
            String fullText
    ) throws Exception {
        return legacyScore(
                strategy,
                pair,
                labeled,
                selected,
                config,
                topK,
                threshold,
                fullText,
                fullText
        );
    }

    private static void decoratePairRow(Map<String, Object> row, PairSpec pair) {
        row.put("domainRelation", pair.domainRelation());
    }

    private static List<Map<String, Object>> aggregateSlices(
            List<Map<String, Object>> pairRows,
            HoldoutRunnerSupport.ProductionConfig config
    ) throws Exception {
        List<Map<String, Object>> out = new ArrayList<>();
        for (String strategy : STRATEGIES) {
            for (String type : PAIR_TYPES) {
                List<Map<String, Object>> rows = pairRows.stream()
                        .filter(row -> strategy.equals(row.get("strategy")))
                        .filter(row -> type.equals(row.get("type")))
                        .toList();
                out.add(aggregateSlice("pair_type", type, strategy, rows, config));
            }
            for (String relation : DOMAIN_RELATIONS) {
                List<Map<String, Object>> rows = pairRows.stream()
                        .filter(row -> strategy.equals(row.get("strategy")))
                        .filter(row -> relation.equals(row.get("domainRelation")))
                        .toList();
                out.add(aggregateSlice("domain_relation", relation, strategy, rows, config));
            }
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> aggregateSlice(
            String dimension,
            String value,
            String strategy,
            List<Map<String, Object>> rows,
            HoldoutRunnerSupport.ProductionConfig config
    ) throws Exception {
        LinkedHashMap<String, Object> out = new LinkedHashMap<>();
        out.put("sliceDimension", dimension);
        out.put("sliceValue", value);
        out.put("strategy", strategy);
        out.put("sampleCount", rows.size());

        if (rows.isEmpty()) {
            out.put("productionConfig", "rag".equals(strategy));
            out.put("chunkSize", config.chunkSize());
            out.put("chunkOverlap", config.chunkOverlap());
            out.put("topK", "rag".equals(strategy) ? config.topK() : 0);
            out.put("threshold", "rag".equals(strategy) ? config.minSimilarity() : 0.0);
            out.put("pairs", 0);
            return out;
        }

        int topK = "rag".equals(strategy) ? config.topK() : 0;
        double threshold = "rag".equals(strategy) ? config.minSimilarity() : 0.0;
        Map<String, Object> legacy = (Map<String, Object>) LEGACY_AGGREGATE_ABLATION_ROWS.invoke(
                null,
                rows,
                strategy,
                config.chunkSize(),
                config.chunkOverlap(),
                topK,
                threshold
        );
        legacy.put("productionConfig", "rag".equals(strategy));
        for (Map.Entry<String, Object> entry : legacy.entrySet()) {
            if (!"strategy".equals(entry.getKey())) {
                out.put(entry.getKey(), entry.getValue());
            }
        }
        return out;
    }

    private static String renderReport(
            DatasetFile dataset,
            List<Map<String, Object>> pairRows,
            List<Map<String, Object>> configRows,
            HoldoutRunnerSupport.ProductionConfig config,
            HoldoutRunnerSupport.RunPlan plan,
            Path datasetDir,
            String commitSha
    ) {
        StringBuilder md = new StringBuilder();
        md.append("# Holdout 单配置评测\n\n");
        md.append("- 运行时间：").append(Instant.now()).append('\n');
        md.append("- 被测 commit：`").append(commitSha).append("`\n");
        md.append("- 数据集：`").append(datasetDir.toAbsolutePath().normalize()).append("`\n");
        md.append("- holdoutVersion：")
                .append(plan.holdoutVersion() == null ? "`(none — smoke)`" : "`" + plan.holdoutVersion() + "`")
                .append('\n');
        md.append("- 生产配置：chunk ")
                .append(config.chunkSize()).append(" / overlap ").append(config.chunkOverlap())
                .append(" / Top-").append(config.topK())
                .append(" / minSimilarity ").append(HoldoutRunnerSupport.formatThreshold(config.minSimilarity()))
                .append("\n\n");

        md.append("## 样本数\n\n");
        md.append("| 维度 | 分层 | 样本数 |\n");
        md.append("|---|---|---:|\n");
        appendCountRows(md, dataset);

        md.append("\n## 分层结果\n\n");
        md.append("| 维度 | 分层 | 策略 | n | 块P | 块R | 块F1 | 证据门P | 证据门R | 证据门F1 | 门控准确率 |\n");
        md.append("|---|---|---|---:|---:|---:|---:|---:|---:|---:|---:|\n");
        for (Map<String, Object> row : configRows) {
            md.append("| ").append(row.get("sliceDimension"))
                    .append(" | ").append(row.get("sliceValue"))
                    .append(" | ").append(row.get("strategy"))
                    .append(" | ").append(row.get("sampleCount"))
                    .append(" | ").append(metric(row, "chunkPrecision"))
                    .append(" | ").append(metric(row, "chunkRecall"))
                    .append(" | ").append(metric(row, "chunkF1"))
                    .append(" | ").append(metric(row, "pairPrecision"))
                    .append(" | ").append(metric(row, "pairRecall"))
                    .append(" | ").append(metric(row, "pairF1"))
                    .append(" | ").append(metric(row, "pairAccuracy"))
                    .append(" |\n");
        }

        long ragRequests = pairRows.stream()
                .filter(row -> "rag".equals(row.get("strategy")))
                .filter(row -> Boolean.TRUE.equals(row.get("predictedEvidenceGate")))
                .count();
        md.append("\n生产 RAG 本次触发证据门的配对数：")
                .append(ragRequests).append(" / ").append(dataset.pairs().size()).append("。\n\n");

        md.append("## 解读限制\n\n");
        md.append("完整限制见 [`PROTOCOL.md` §6](PROTOCOL.md)。尤其要注意：块级金标由人工挑定的 ")
                .append("`goldPhrases` 按字面命中派生，会系统性偏袒关键词通路；")
                .append("因此块级 P/R/F1 只能在这一金标定义下解读，不能当成最终 LLM 匹配准确率。\n");
        return md.toString();
    }

    private static void appendCountRows(StringBuilder md, DatasetFile dataset) {
        for (String type : PAIR_TYPES) {
            long count = dataset.pairs().stream().filter(pair -> type.equals(pair.type())).count();
            md.append("| pair_type | ").append(type).append(" | ").append(count).append(" |\n");
        }
        for (String relation : DOMAIN_RELATIONS) {
            long count = dataset.pairs().stream()
                    .filter(pair -> relation.equals(pair.domainRelation()))
                    .count();
            md.append("| domain_relation | ").append(relation).append(" | ").append(count).append(" |\n");
        }
    }

    private static String metric(Map<String, Object> row, String key) {
        Object value = row.get(key);
        if (!(value instanceof Number number)) {
            return "—";
        }
        return String.format(Locale.ROOT, "%.3f", number.doubleValue());
    }

    private static DatasetFile loadDataset(Path datasetDir, ObjectMapper mapper) throws Exception {
        DatasetFile dataset = mapper.readValue(datasetDir.resolve("pairs.json").toFile(), DatasetFile.class);
        if (dataset.pairs() == null) {
            return dataset;
        }
        List<PairSpec> normalized = new ArrayList<>();
        for (PairSpec pair : dataset.pairs()) {
            normalized.add(pair.withNormalizedDomainRelation());
        }
        return new DatasetFile(
                dataset.version(),
                dataset.notes(),
                dataset.holdoutVersion(),
                dataset.resumes(),
                dataset.jobs(),
                List.copyOf(normalized)
        );
    }

    private static Map<String, Resume> materializeResumes(
            Path datasetDir,
            DatasetFile dataset,
            AppUser user
    ) throws Exception {
        Map<String, Resume> out = new LinkedHashMap<>();
        long id = 10_000L;
        for (ResumeSpec spec : dataset.resumes()) {
            Resume resume = new Resume();
            setId(resume, id++);
            resume.setUser(user);
            resume.setTitle(spec.title());
            resume.setCandidateName(spec.candidateName());
            resume.setRawText(Files.readString(datasetDir.resolve(spec.file()), StandardCharsets.UTF_8));
            out.put(spec.id(), resume);
        }
        return out;
    }

    private static Map<String, JobDescription> materializeJobs(
            Path datasetDir,
            DatasetFile dataset,
            AppUser user
    ) throws Exception {
        Map<String, JobDescription> out = new LinkedHashMap<>();
        long id = 20_000L;
        for (JobSpec spec : dataset.jobs()) {
            String text = Files.readString(datasetDir.resolve(spec.file()), StandardCharsets.UTF_8);
            JobDescription job = new JobDescription();
            setId(job, id++);
            job.setUser(user);
            job.setTitle(spec.title());
            job.setCompanyName(spec.companyName());
            job.setLocation(spec.location());
            job.setEmploymentType(spec.employmentType());
            int requirementsAt = text.indexOf("任职要求");
            if (requirementsAt >= 0) {
                job.setDescription(text.substring(0, requirementsAt).trim());
                job.setRequirements(text.substring(requirementsAt).trim());
            } else {
                job.setDescription(text.trim());
                job.setRequirements("");
            }
            out.put(spec.id(), job);
        }
        return out;
    }

    private static AppUser experimentUser() throws Exception {
        AppUser user = new AppUser();
        setId(user, 1L);
        user.setUsername("holdout-runner");
        user.setEmail("holdout-runner@example.com");
        user.setDisplayName("holdout-runner");
        user.setPasswordHash("not-used");
        return user;
    }

    private static <T> T requirePresent(Map<String, T> values, String id, String kind, String pairId) {
        T value = values.get(id);
        if (value == null) {
            throw new IllegalStateException("missing " + kind + " " + id + " for pair " + pairId);
        }
        return value;
    }

    private static Path resolveRepoRoot() {
        Path cwd = Path.of("").toAbsolutePath().normalize();
        if (Files.isRegularFile(cwd.resolve("experiments").resolve("holdout").resolve("PROTOCOL.md"))) {
            return cwd;
        }
        Path parent = cwd.getParent();
        if (parent != null
                && Files.isRegularFile(parent.resolve("experiments").resolve("holdout").resolve("PROTOCOL.md"))) {
            return parent;
        }
        throw new IllegalStateException("cannot locate repository root from " + cwd);
    }

    private static Path resolveDatasetDir(Path repoRoot) {
        String override = System.getenv("HOLDOUT_DIR");
        if (override != null && !override.isBlank()) {
            return Path.of(override).toAbsolutePath().normalize();
        }
        return repoRoot.resolve("experiments").resolve("holdout").resolve("dataset");
    }

    private static String resolveCommitSha(Path repoRoot) throws Exception {
        String githubSha = System.getenv("GITHUB_SHA");
        if (githubSha != null && githubSha.matches("[0-9a-fA-F]{40}")) {
            return githubSha.toLowerCase(Locale.ROOT);
        }
        Process process = new ProcessBuilder("git", "-C", repoRoot.toString(), "rev-parse", "HEAD")
                .redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        if (process.waitFor() != 0 || !output.matches("[0-9a-fA-F]{40}")) {
            throw new IllegalStateException("cannot determine tested commit SHA: " + output);
        }
        return output.toLowerCase(Locale.ROOT);
    }

    private static String resolveModelResource(String environmentVariable, Path localPath, String configuredUri) {
        String override = System.getenv(environmentVariable);
        if (override != null && !override.isBlank()) {
            return override;
        }
        Path absolute = localPath.toAbsolutePath().normalize();
        return Files.isRegularFile(absolute) ? absolute.toUri().toString() : configuredUri;
    }

    private static void assertLocalModelPresent(String tokenizerUri, String modelUri, PrintStream log) {
        Path tokenizer = uriToExistingFile(tokenizerUri);
        Path model = uriToExistingFile(modelUri);
        if (tokenizer == null || model == null) {
            throw new IllegalStateException(
                    "ONNX assets are not local files; refusing to download during holdout. "
                            + "tokenizer=" + tokenizerUri + " model=" + modelUri
            );
        }
        log("local tokenizer bytes=" + tokenizer.toFile().length(), log);
        log("local model bytes=" + model.toFile().length(), log);
        if (model.toFile().length() < 100_000_000L) {
            throw new IllegalStateException(
                    "ONNX model file looks truncated: " + model + " bytes=" + model.toFile().length()
            );
        }
    }

    private static Path uriToExistingFile(String uri) {
        if (uri == null) {
            return null;
        }
        try {
            Path path = uri.startsWith("file:")
                    ? Path.of(java.net.URI.create(uri))
                    : Path.of(uri);
            return Files.isRegularFile(path) ? path : null;
        } catch (RuntimeException ex) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static ResumeChunkRepository legacyInMemoryRepository(Map<Long, List<ResumeChunk>> store)
            throws Exception {
        return (ResumeChunkRepository) LEGACY_IN_MEMORY_REPOSITORY.invoke(null, store);
    }

    private static Class<?> nestedClass(String simpleName) {
        for (Class<?> nested : ThresholdSweepExperimentTests.class.getDeclaredClasses()) {
            if (simpleName.equals(nested.getSimpleName())) {
                return nested;
            }
        }
        throw new IllegalStateException("legacy nested class not found: " + simpleName);
    }

    private static Constructor<?> privateConstructor(Class<?> type, Class<?>... arguments) {
        try {
            Constructor<?> constructor = type.getDeclaredConstructor(arguments);
            constructor.setAccessible(true);
            return constructor;
        } catch (ReflectiveOperationException ex) {
            throw new ExceptionInInitializerError(ex);
        }
    }

    private static Method privateMethod(String name, Class<?>... arguments) {
        try {
            Method method = ThresholdSweepExperimentTests.class.getDeclaredMethod(name, arguments);
            method.setAccessible(true);
            return method;
        } catch (ReflectiveOperationException ex) {
            throw new ExceptionInInitializerError(ex);
        }
    }

    private static void setId(Object entity, Long id) throws Exception {
        Field field = entity.getClass().getDeclaredField("id");
        field.setAccessible(true);
        field.set(entity, id);
    }

    private static int utf8Bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8).length;
    }

    private static double elapsedSeconds(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000_000.0;
    }

    private static ObjectMapper mapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.enable(SerializationFeature.INDENT_OUTPUT);
        return mapper;
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

    private static void log(String message, PrintStream log) {
        System.out.println(message);
        log.println(message);
    }

    private static void deleteRecursively(Path root) throws Exception {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (var paths = Files.walk(root)) {
            List<Path> ordered = paths.sorted(Comparator.reverseOrder()).toList();
            for (Path path : ordered) {
                Files.deleteIfExists(path);
            }
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record DatasetFile(
            int version,
            String notes,
            String holdoutVersion,
            List<ResumeSpec> resumes,
            List<JobSpec> jobs,
            List<PairSpec> pairs
    ) {
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
            String notes,
            String domainRelation
    ) {
        PairSpec {
            if (goldPhrases == null) {
                goldPhrases = List.of();
            } else {
                goldPhrases = List.copyOf(goldPhrases);
            }
        }

        PairSpec withNormalizedDomainRelation() {
            return new PairSpec(
                    id,
                    type,
                    resumeId,
                    jobId,
                    shouldMatch,
                    goldPhrases,
                    notes,
                    HoldoutRunnerSupport.normalizeDomainRelation(domainRelation)
            );
        }
    }
}
