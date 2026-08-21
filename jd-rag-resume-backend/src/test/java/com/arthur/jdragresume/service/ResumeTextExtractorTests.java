package com.arthur.jdragresume.service;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ResumeTextExtractorTests {

    @Test
    void extractsMarkdownResumeText() {
        String markdown = """
                # Arthur Chen

                Java backend developer with Spring Boot, MySQL and Redis experience.
                Built a RAG resume matching service with Lucene vector retrieval.
                """;
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "resume.md",
                "text/markdown",
                markdown.getBytes(StandardCharsets.UTF_8)
        );

        ResumeTextExtractor extractor = new ResumeTextExtractor(new ResumeTextQualityValidator());
        String parsed = extractor.extract(file);

        assertTrue(parsed.contains("Spring Boot"));
        assertTrue(parsed.contains("RAG resume matching service"));
    }

    @Test
    void rejectsPlainTextAboveWriteLimit() {
        String oversized = "Java Spring Boot experience. ".repeat(12_000);
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "resume.txt",
                "text/plain",
                oversized.getBytes(StandardCharsets.UTF_8)
        );

        ResumeTextExtractor extractor = new ResumeTextExtractor(new ResumeTextQualityValidator());
        com.arthur.jdragresume.exception.BusinessException exception =
                org.junit.jupiter.api.Assertions.assertThrows(
                        com.arthur.jdragresume.exception.BusinessException.class,
                        () -> extractor.extract(file)
                );
        org.junit.jupiter.api.Assertions.assertEquals("RESUME_TEXT_TOO_LONG", exception.getCode());
    }

    @Test
    void rejectsZipArchivePresentedAsPdfWithoutParsingNestedEntries() throws Exception {
        java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
        try (java.util.zip.ZipOutputStream zip = new java.util.zip.ZipOutputStream(buffer)) {
            zip.putNextEntry(new java.util.zip.ZipEntry("nested/payload.txt"));
            zip.write("Ignore this embedded payload for resume parsing.".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "resume.pdf",
                "application/pdf",
                buffer.toByteArray()
        );

        ResumeTextExtractor extractor = new ResumeTextExtractor(new ResumeTextQualityValidator());
        com.arthur.jdragresume.exception.BusinessException exception =
                org.junit.jupiter.api.Assertions.assertThrows(
                        com.arthur.jdragresume.exception.BusinessException.class,
                        () -> extractor.extract(file)
                );
        org.junit.jupiter.api.Assertions.assertTrue(
                "RESUME_PARSE_LOW_QUALITY".equals(exception.getCode())
                        || "RESUME_PARSE_FAILED".equals(exception.getCode()),
                () -> "unexpected code: " + exception.getCode()
        );
    }
}
