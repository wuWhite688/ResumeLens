package com.arthur.jdragresume.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HoldoutRunnerSupportTests {
    @TempDir
    Path tempDir;

    @Test
    void productionConfigComesFromCommittedApplicationProperties() {
        HoldoutRunnerSupport.ProductionConfig config = HoldoutRunnerSupport.loadProductionConfig();

        assertTrue(config.chunkSize() > 0);
        assertTrue(config.chunkOverlap() >= 0);
        assertTrue(config.chunkOverlap() < config.chunkSize());
        assertTrue(config.topK() > 0);
        assertTrue(config.minSimilarity() >= 0.0 && config.minSimilarity() <= 1.0);
    }

    @Test
    void missingDomainRelationDefaultsToSeenDomain() {
        assertEquals(HoldoutRunnerSupport.SEEN_DOMAIN, HoldoutRunnerSupport.normalizeDomainRelation(null));
        assertEquals(HoldoutRunnerSupport.SEEN_DOMAIN, HoldoutRunnerSupport.normalizeDomainRelation(""));
        assertEquals(
                HoldoutRunnerSupport.NEW_DOMAIN,
                HoldoutRunnerSupport.normalizeDomainRelation(HoldoutRunnerSupport.NEW_DOMAIN)
        );
        assertThrows(
                IllegalStateException.class,
                () -> HoldoutRunnerSupport.normalizeDomainRelation("mystery_domain")
        );
    }

    @Test
    void devStyleDatasetIsSmokeAndDoesNotTouchRunLog() throws Exception {
        Path holdoutRoot = tempDir.resolve("holdout");
        Path dataset = tempDir.resolve("dev-dataset");
        Path runLog = holdoutRoot.resolve("RUN-LOG.md");
        Files.createDirectories(dataset);
        Files.createDirectories(holdoutRoot);
        Files.writeString(dataset.resolve("pairs.json"), "{}", StandardCharsets.UTF_8);
        Files.writeString(runLog, runLogWithV1(), StandardCharsets.UTF_8);
        String before = Files.readString(runLog, StandardCharsets.UTF_8);
        RecordingGuard guard = new RecordingGuard();

        HoldoutRunnerSupport.RunPlan plan =
                HoldoutRunnerSupport.planRun(dataset, holdoutRoot, runLog, new ObjectMapper(), guard);

        assertFalse(plan.formal());
        assertEquals(holdoutRoot.resolve(".smoke"), plan.outputRoot());
        assertEquals(before, Files.readString(runLog, StandardCharsets.UTF_8));
        assertTrue(guard.invocations.isEmpty(), "smoke runs must not be subject to the freeze guard");
    }

    @Test
    void v1DatasetUsesExistingSectionAndAppendsPendingRow() throws Exception {
        Path holdoutRoot = tempDir.resolve("holdout");
        Path dataset = tempDir.resolve("v1-dataset");
        Path runLog = holdoutRoot.resolve("RUN-LOG.md");
        Files.createDirectories(dataset);
        Files.createDirectories(holdoutRoot);
        Files.writeString(
                dataset.resolve("pairs.json"),
                "{\"holdoutVersion\":\"v1\"}",
                StandardCharsets.UTF_8
        );
        Files.writeString(runLog, runLogWithV1(), StandardCharsets.UTF_8);

        HoldoutRunnerSupport.RunPlan plan = HoldoutRunnerSupport.planRun(
                dataset, holdoutRoot, runLog, new ObjectMapper(), HoldoutRunnerSupport.NO_FREEZE_GUARD
        );
        int ordinal = HoldoutRunnerSupport.appendPendingRun(
                runLog,
                plan.holdoutVersion(),
                new HoldoutRunnerSupport.ProductionConfig(900, 120, 5, 0.72),
                "0123456789abcdef0123456789abcdef01234567",
                "experiments/holdout/RESULTS.md",
                "test run"
        );

        String updated = Files.readString(runLog, StandardCharsets.UTF_8);
        assertTrue(plan.formal());
        assertEquals("v1", plan.holdoutVersion());
        assertEquals(1, ordinal);
        assertTrue(updated.contains("| 1 |"));
        assertTrue(updated.contains("| 待判定 |"));
        assertTrue(updated.contains("test run"));
        assertFalse(updated.contains("| — | — |"));
    }

    @Test
    void unknownHoldoutVersionFailsInsteadOfCreatingSection() throws Exception {
        Path holdoutRoot = tempDir.resolve("holdout");
        Path dataset = tempDir.resolve("v9-dataset");
        Path runLog = holdoutRoot.resolve("RUN-LOG.md");
        Files.createDirectories(dataset);
        Files.createDirectories(holdoutRoot);
        Files.writeString(
                dataset.resolve("pairs.json"),
                "{\"holdoutVersion\":\"v9\"}",
                StandardCharsets.UTF_8
        );
        Files.writeString(runLog, runLogWithV1(), StandardCharsets.UTF_8);
        String before = Files.readString(runLog, StandardCharsets.UTF_8);

        IllegalStateException error = assertThrows(
                IllegalStateException.class,
                () -> HoldoutRunnerSupport.planRun(dataset, holdoutRoot, runLog, new ObjectMapper())
        );

        assertTrue(error.getMessage().contains("## v9"));
        assertEquals(before, Files.readString(runLog, StandardCharsets.UTF_8));
    }

    @Test
    void formalRunAppliesFreezeGuardWithRepoRootAndDataset() throws Exception {
        Path repoRoot = tempDir.resolve("repo");
        Path holdoutRoot = repoRoot.resolve("experiments").resolve("holdout");
        Path dataset = holdoutRoot.resolve("dataset");
        Path runLog = holdoutRoot.resolve("RUN-LOG.md");
        Files.createDirectories(dataset);
        Files.writeString(
                dataset.resolve("pairs.json"),
                "{\"holdoutVersion\":\"v1\"}",
                StandardCharsets.UTF_8
        );
        Files.writeString(runLog, runLogWithV1(), StandardCharsets.UTF_8);
        RecordingGuard guard = new RecordingGuard();

        HoldoutRunnerSupport.RunPlan plan =
                HoldoutRunnerSupport.planRun(dataset, holdoutRoot, runLog, new ObjectMapper(), guard);

        assertTrue(plan.formal());
        assertEquals(1, guard.invocations.size());
        assertEquals(
                repoRoot.toAbsolutePath().normalize(),
                guard.invocations.get(0)[0].toAbsolutePath().normalize()
        );
        assertEquals(
                dataset.toAbsolutePath().normalize(),
                guard.invocations.get(0)[1].toAbsolutePath().normalize()
        );
    }

    @Test
    void freezeGuardFailureAbortsBeforeAnyRunLogWrite() throws Exception {
        Path holdoutRoot = tempDir.resolve("holdout");
        Path dataset = tempDir.resolve("v1-dataset");
        Path runLog = holdoutRoot.resolve("RUN-LOG.md");
        Files.createDirectories(dataset);
        Files.createDirectories(holdoutRoot);
        Files.writeString(
                dataset.resolve("pairs.json"),
                "{\"holdoutVersion\":\"v1\"}",
                StandardCharsets.UTF_8
        );
        Files.writeString(runLog, runLogWithV1(), StandardCharsets.UTF_8);
        String before = Files.readString(runLog, StandardCharsets.UTF_8);

        IllegalStateException error = assertThrows(
                IllegalStateException.class,
                () -> HoldoutRunnerSupport.planRun(
                        dataset,
                        holdoutRoot,
                        runLog,
                        new ObjectMapper(),
                        (repoRoot, datasetDir) -> {
                            throw new IllegalStateException("frozen-check boom");
                        }
                )
        );

        assertEquals("frozen-check boom", error.getMessage());
        assertEquals(before, Files.readString(runLog, StandardCharsets.UTF_8));
    }

    @Test
    void cleanWorktreeOutputIsAccepted() {
        HoldoutRunnerSupport.assertCleanWorktree("");
        HoldoutRunnerSupport.assertCleanWorktree("\n  \n");
    }

    @Test
    void dirtyWorktreeOutputIsRejectedAndNamesTheEntries() {
        IllegalStateException error = assertThrows(
                IllegalStateException.class,
                () -> HoldoutRunnerSupport.assertCleanWorktree(
                        " M experiments/holdout/dataset/pairs.json\n"
                                + "?? experiments/holdout/dataset/resumes/r13.md\n"
                )
        );

        assertTrue(error.getMessage().contains("2 entries"));
        assertTrue(error.getMessage().contains("pairs.json"));
        assertTrue(error.getMessage().contains("r13.md"));
    }

    @Test
    void datasetOutsideRepositoryIsRejected() throws Exception {
        Path repoRoot = tempDir.resolve("repo");
        Path inside = repoRoot.resolve("experiments").resolve("holdout").resolve("dataset");
        Path outside = tempDir.resolve("elsewhere").resolve("dataset");
        Files.createDirectories(inside);
        Files.createDirectories(outside);

        HoldoutRunnerSupport.requireDatasetInsideRepo(repoRoot, inside);

        IllegalStateException error = assertThrows(
                IllegalStateException.class,
                () -> HoldoutRunnerSupport.requireDatasetInsideRepo(repoRoot, outside)
        );
        assertTrue(error.getMessage().contains("inside the repository"));
    }

    @Test
    void repoRootIsDerivedFromTheHoldoutRoot() {
        Path repoRoot = tempDir.resolve("repo");
        Path holdoutRoot = repoRoot.resolve("experiments").resolve("holdout");

        assertEquals(
                repoRoot.toAbsolutePath().normalize(),
                HoldoutRunnerSupport.repoRootOf(holdoutRoot)
        );
    }

    private static final class RecordingGuard implements HoldoutRunnerSupport.FreezeGuard {
        private final List<Path[]> invocations = new ArrayList<>();

        @Override
        public void check(Path repoRoot, Path datasetDir) {
            invocations.add(new Path[] {repoRoot, datasetDir});
        }
    }

    private static String runLogWithV1() {
        return """
                # holdout 运行登记

                ## v1

                | # | 日期 | 被测 commit | 参数（chunk/overlap、Top-K、minSimilarity） | 结果文件 | 性质 | 备注 |
                |---|------|-------------|------------------------------------------|---------|------|------|
                | — | —    | —           | —                                        | —       | —    | 尚未运行 |
                """;
    }
}
