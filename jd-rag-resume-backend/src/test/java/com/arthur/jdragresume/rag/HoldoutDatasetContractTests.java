package com.arthur.jdragresume.rag;

import com.arthur.jdragresume.entity.JobDescription;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HoldoutDatasetContractTests {
    private static final Method EXTRACT_KEYWORDS = privateMethod("extractKeywords", JobDescription.class);
    private static final Method MATCH_KEYWORDS = privateMethod("matchKeywords", String.class, List.class);
    private static final Method CONTAINS_ALIAS = privateMethod("containsAlias", String.class, List.class);
    private static final Field HARD_SKILL_ALIASES = privateField("HARD_SKILL_ALIASES");

    @Test
    void frozenV1DatasetKeepsGoldAndChunkContracts() throws Exception {
        Path repoRoot = resolveRepoRoot();
        Path datasetDir = repoRoot.resolve("experiments").resolve("holdout").resolve("dataset");
        ObjectMapper mapper = new ObjectMapper();
        JsonNode root = mapper.readTree(datasetDir.resolve("pairs.json").toFile());

        assertEquals("v1", root.path("holdoutVersion").asText());

        Map<String, JsonNode> resumes = indexById(root.path("resumes"));
        Map<String, JsonNode> jobs = indexById(root.path("jobs"));
        Map<String, String> resumeTexts = loadTexts(datasetDir, resumes);
        Map<String, String> jobTexts = loadTexts(datasetDir, jobs);

        HoldoutRunnerSupport.ProductionConfig config = HoldoutRunnerSupport.loadProductionConfig();
        RagProperties properties = new RagProperties();
        properties.setChunkSize(config.chunkSize());
        properties.setChunkOverlap(config.chunkOverlap());
        properties.setTopK(config.topK());
        properties.setMinSimilarity(config.minSimilarity());
        TextChunker chunker = new TextChunker(properties);
        ResumeRagService lexicalProbe = new ResumeRagService(
                null,
                null,
                null,
                properties,
                null,
                null,
                null
        );

        for (Map.Entry<String, String> entry : resumeTexts.entrySet()) {
            int chunks = chunker.split(entry.getValue()).size();
            assertTrue(
                    chunks >= 2,
                    () -> "holdout v1 resume " + entry.getKey()
                            + " collapsed to " + chunks + " chunk(s) at production "
                            + config.chunkSize() + "/" + config.chunkOverlap()
                            + "; do not edit frozen v1 to compensate—create a new holdout version"
            );
        }

        Map<String, Integer> typeCounts = new HashMap<>();
        Map<String, Integer> domainCounts = new HashMap<>();
        Map<String, Set<String>> negativeTargetJobsByDomain = new HashMap<>();
        int mixedGoldCount = 0;

        for (JsonNode pair : root.path("pairs")) {
            String id = requiredText(pair, "id");
            String type = requiredText(pair, "type");
            String resumeId = requiredText(pair, "resumeId");
            String jobId = requiredText(pair, "jobId");
            String domainRelation = HoldoutRunnerSupport.normalizeDomainRelation(
                    requiredText(pair, "domainRelation")
            );

            assertTrue(resumes.containsKey(resumeId), id + " references unknown resume " + resumeId);
            assertTrue(jobs.containsKey(jobId), id + " references unknown job " + jobId);

            typeCounts.merge(type, 1, Integer::sum);
            domainCounts.merge(domainRelation, 1, Integer::sum);

            List<String> gold = strings(pair.path("goldPhrases"));
            if (!"positive".equals(type)) {
                assertFalse(pair.path("shouldMatch").asBoolean(), id + " non-positive shouldMatch must be false");
                assertTrue(gold.isEmpty(), id + " non-positive goldPhrases must be empty");
                assertTrue(
                        pair.path("goldPhraseKinds").isMissingNode() || pair.path("goldPhraseKinds").isNull(),
                        id + " non-positive pair must not define goldPhraseKinds"
                );
                if ("negative".equals(type)) {
                    negativeTargetJobsByDomain
                            .computeIfAbsent(domainRelation, ignored -> new HashSet<>())
                            .add(jobId);
                }
                continue;
            }

            assertTrue(pair.path("shouldMatch").asBoolean(), id + " positive shouldMatch must be true");
            assertFalse(gold.isEmpty(), id + " positive goldPhrases must not be empty");
            assertEquals(gold.size(), new LinkedHashSet<>(gold).size(), id + " goldPhrases contain duplicates");

            JsonNode kinds = pair.path("goldPhraseKinds");
            assertTrue(kinds.isObject(), id + " must define goldPhraseKinds");
            List<String> lexical = strings(kinds.path("lexical"));
            List<String> mixed = strings(kinds.path("mixed"));
            List<String> semantic = strings(kinds.path("semantic"));
            assertFalse(lexical.isEmpty(), id + " must keep at least one lexical gold phrase");
            assertFalse(semantic.isEmpty(), id + " must keep at least one semantic gold phrase");
            mixedGoldCount += mixed.size();

            Map<String, String> classified = new LinkedHashMap<>();
            addClassifications(classified, lexical, "lexical", id);
            addClassifications(classified, mixed, "mixed", id);
            addClassifications(classified, semantic, "semantic", id);
            assertEquals(new LinkedHashSet<>(gold), classified.keySet(), id + " goldPhraseKinds must cover goldPhrases exactly");

            String resumeText = assertPresent(resumeTexts, resumeId, id, "resume");
            String jobText = assertPresent(jobTexts, jobId, id, "job");
            JsonNode jobSpec = jobs.get(jobId);

            for (String phrase : gold) {
                assertTrue(
                        resumeText.contains(phrase),
                        () -> id + " goldPhrase is not present in resume text: " + phrase
                );

                boolean verbatimInJd = jobText.contains(phrase);
                boolean productionLexicalHit = productionLexicalHit(
                        lexicalProbe,
                        jobSpec,
                        jobText,
                        phrase
                );
                String expectedKind = verbatimInJd
                        ? "lexical"
                        : productionLexicalHit ? "mixed" : "semantic";
                assertEquals(
                        expectedKind,
                        classified.get(phrase),
                        () -> id + " goldPhrase kind does not match the current production lexical path: "
                                + phrase + " (verbatimInJd=" + verbatimInJd
                                + ", productionLexicalHit=" + productionLexicalHit + ")"
                );
            }

            List<String> chunks = chunker.split(resumeText);
            boolean hasGoldRelevantChunk = chunks.stream().anyMatch(
                    chunk -> gold.stream().anyMatch(chunk::contains)
            );
            assertTrue(hasGoldRelevantChunk, id + " has zero gold-relevant chunks at production chunk config");
        }

        assertEquals(12, typeCounts.getOrDefault("positive", 0));
        assertEquals(12, typeCounts.getOrDefault("negative", 0));
        assertEquals(12, typeCounts.getOrDefault("hard_negative", 0));
        assertEquals(18, domainCounts.getOrDefault(HoldoutRunnerSupport.SEEN_DOMAIN, 0));
        assertEquals(18, domainCounts.getOrDefault(HoldoutRunnerSupport.NEW_DOMAIN, 0));
        assertTrue(mixedGoldCount > 0, "holdout v1 should exercise at least one mixed lexical/semantic gold phrase");

        assertEquals(
                6,
                negativeTargetJobsByDomain.getOrDefault(HoldoutRunnerSupport.SEEN_DOMAIN, Set.of()).size(),
                "seen_domain negative pairs should spread across all six target JDs"
        );
        assertEquals(
                6,
                negativeTargetJobsByDomain.getOrDefault(HoldoutRunnerSupport.NEW_DOMAIN, Set.of()).size(),
                "new_domain negative pairs should spread across all six target JDs"
        );
    }

    @SuppressWarnings("unchecked")
    private static boolean productionLexicalHit(
            ResumeRagService lexicalProbe,
            JsonNode jobSpec,
            String jobText,
            String phrase
    ) throws Exception {
        JobDescription job = materializeJob(jobSpec, jobText);
        List<String> keywords = (List<String>) EXTRACT_KEYWORDS.invoke(lexicalProbe, job);
        List<String> keywordHits = (List<String>) MATCH_KEYWORDS.invoke(lexicalProbe, phrase, keywords);
        if (!keywordHits.isEmpty()) {
            return true;
        }

        String jdBlob = String.join("\n",
                nullToEmpty(job.getTitle()),
                nullToEmpty(job.getDescription()),
                nullToEmpty(job.getRequirements())
        ).toLowerCase(Locale.ROOT);
        String evidence = phrase.toLowerCase(Locale.ROOT);
        Map<String, List<String>> aliases = (Map<String, List<String>>) HARD_SKILL_ALIASES.get(null);
        for (List<String> aliasSet : aliases.values()) {
            boolean requiredByJd = (boolean) CONTAINS_ALIAS.invoke(null, jdBlob, aliasSet);
            boolean suppliedByPhrase = (boolean) CONTAINS_ALIAS.invoke(null, evidence, aliasSet);
            if (requiredByJd && suppliedByPhrase) {
                return true;
            }
        }
        return false;
    }

    private static JobDescription materializeJob(JsonNode spec, String text) {
        JobDescription job = new JobDescription();
        job.setTitle(requiredText(spec, "title"));
        job.setCompanyName(requiredText(spec, "companyName"));
        job.setLocation(requiredText(spec, "location"));
        job.setEmploymentType(requiredText(spec, "employmentType"));
        int requirementsAt = text.indexOf("任职要求");
        if (requirementsAt >= 0) {
            job.setDescription(text.substring(0, requirementsAt).trim());
            job.setRequirements(text.substring(requirementsAt).trim());
        } else {
            job.setDescription(text.trim());
            job.setRequirements("");
        }
        return job;
    }

    private static void addClassifications(
            Map<String, String> classified,
            List<String> phrases,
            String kind,
            String pairId
    ) {
        for (String phrase : phrases) {
            assertTrue(
                    classified.put(phrase, kind) == null,
                    pairId + " phrase classified more than once: " + phrase
            );
        }
    }

    private static Method privateMethod(String name, Class<?>... arguments) {
        try {
            Method method = ResumeRagService.class.getDeclaredMethod(name, arguments);
            method.setAccessible(true);
            return method;
        } catch (ReflectiveOperationException ex) {
            throw new ExceptionInInitializerError(ex);
        }
    }

    private static Field privateField(String name) {
        try {
            Field field = ResumeRagService.class.getDeclaredField(name);
            field.setAccessible(true);
            return field;
        } catch (ReflectiveOperationException ex) {
            throw new ExceptionInInitializerError(ex);
        }
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static Map<String, JsonNode> indexById(JsonNode values) {
        assertTrue(values.isArray(), "dataset collection must be an array");
        Map<String, JsonNode> out = new HashMap<>();
        for (JsonNode value : values) {
            String id = requiredText(value, "id");
            assertTrue(out.put(id, value) == null, "duplicate dataset id " + id);
        }
        return out;
    }

    private static Map<String, String> loadTexts(Path datasetDir, Map<String, JsonNode> specs) throws Exception {
        Map<String, String> out = new HashMap<>();
        for (Map.Entry<String, JsonNode> entry : specs.entrySet()) {
            String file = requiredText(entry.getValue(), "file");
            Path path = datasetDir.resolve(file).normalize();
            assertTrue(path.startsWith(datasetDir.normalize()), "dataset path escapes root: " + file);
            assertTrue(Files.isRegularFile(path), "missing dataset file " + path);
            out.put(entry.getKey(), Files.readString(path, StandardCharsets.UTF_8));
        }
        return out;
    }

    private static List<String> strings(JsonNode node) {
        assertTrue(node.isArray(), "expected JSON array");
        List<String> out = new ArrayList<>();
        for (JsonNode value : node) {
            assertTrue(value.isTextual() && !value.asText().isBlank(), "array values must be non-blank strings");
            out.add(value.asText());
        }
        return out;
    }

    private static String requiredText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        assertNotNull(value, "missing field " + field);
        assertTrue(value.isTextual() && !value.asText().isBlank(), "field " + field + " must be non-blank text");
        return value.asText();
    }

    private static String assertPresent(
            Map<String, String> values,
            String id,
            String pairId,
            String kind
    ) {
        String value = values.get(id);
        assertNotNull(value, pairId + " references missing " + kind + " text " + id);
        return value;
    }

    private static Path resolveRepoRoot() {
        Path current = Path.of("").toAbsolutePath().normalize();
        if (Files.isRegularFile(current.resolve("experiments").resolve("holdout").resolve("PROTOCOL.md"))) {
            return current;
        }
        Path parent = current.getParent();
        if (parent != null
                && Files.isRegularFile(parent.resolve("experiments").resolve("holdout").resolve("PROTOCOL.md"))) {
            return parent;
        }
        throw new IllegalStateException("cannot resolve repository root from " + current);
    }
}
