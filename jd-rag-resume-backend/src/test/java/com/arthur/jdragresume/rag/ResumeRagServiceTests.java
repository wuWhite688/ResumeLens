package com.arthur.jdragresume.rag;

import org.junit.jupiter.api.Test;

import com.arthur.jdragresume.entity.JobDescription;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResumeRagServiceTests {
    @Test
    void calculatesCosineSimilarity() {
        assertEquals(1.0, ResumeRagService.cosineSimilarity(
                new float[]{1.0f, 2.0f},
                new float[]{1.0f, 2.0f}
        ), 0.000001);
        assertEquals(0.0, ResumeRagService.cosineSimilarity(
                new float[]{1.0f, 0.0f},
                new float[]{0.0f, 1.0f}
        ), 0.000001);
    }

    @Test
    void rejectsMismatchedVectorDimensions() {
        assertEquals(0.0, ResumeRagService.cosineSimilarity(
                new float[]{1.0f},
                new float[]{1.0f, 2.0f}
        ));
    }

    @Test
    void detectSectionFromChunkHeadings() {
        assertEquals("技能", TextChunker.detectSection("技能\nJava Spring Boot MySQL"));
        assertEquals("项目", TextChunker.detectSection("项目经历\n负责 RAG 模块"));
        assertTrue(TextChunker.detectSection("普通正文没有标题").length() > 0);
    }

    @Test
    void computesDeterministicHardSkillCoverageAndScoreCap() {
        ResumeRagService service = new ResumeRagService(null, null, null, new RagProperties(), null, null, null);
        JobDescription jd = new JobDescription();
        jd.setTitle("Java 后端工程师");
        jd.setDescription("使用 Spring Boot 与 Redis 开发服务");
        jd.setRequirements("熟悉 MySQL 和 Docker");
        List<RetrievedChunk> evidence = List.of(new RetrievedChunk(
                0,
                "熟悉 Java、Spring Boot、MySQL",
                0.8,
                0.8,
                true,
                "kept",
                "技能",
                List.of()
        ));

        HardSkillCoverage coverage = service.assessHardSkills(jd, evidence);

        assertEquals(List.of("Docker", "Redis"), coverage.missing().stream().sorted().toList());
        assertEquals(3, coverage.matched().size());
        assertEquals("80.00", coverage.scoreCap().toPlainString());
    }

    @Test
    void matchesAsciiSkillsAdjacentToChineseCharacters() {
        ResumeRagService service = new ResumeRagService(null, null, null, new RagProperties(), null, null, null);
        JobDescription jd = new JobDescription();
        jd.setTitle("Java后端工程师");
        jd.setDescription("熟悉SpringBoot和Redis");
        jd.setRequirements("了解Docker部署");
        List<RetrievedChunk> evidence = List.of(new RetrievedChunk(
                0,
                "熟悉Java开发，用过SpringBoot、Redis",
                0.8,
                0.8,
                true,
                "kept",
                "技能",
                List.of()
        ));

        HardSkillCoverage coverage = service.assessHardSkills(jd, evidence);

        assertEquals(
                List.of("Docker", "Java", "Redis", "Spring Boot"),
                coverage.required().stream().sorted().toList()
        );
        assertEquals(
                List.of("Java", "Redis", "Spring Boot"),
                coverage.matched().stream().sorted().toList()
        );
        assertEquals(List.of("Docker"), coverage.missing());
    }

    @Test
    void doesNotMatchSkillNameInsideLongerAsciiWord() {
        ResumeRagService service = new ResumeRagService(null, null, null, new RagProperties(), null, null, null);
        JobDescription jd = new JobDescription();
        jd.setTitle("javascript工程师");
        jd.setDescription("精通javascript");
        jd.setRequirements("");

        HardSkillCoverage coverage = service.assessHardSkills(jd, List.of());

        assertEquals(List.of("JavaScript"), coverage.required());
    }
}
