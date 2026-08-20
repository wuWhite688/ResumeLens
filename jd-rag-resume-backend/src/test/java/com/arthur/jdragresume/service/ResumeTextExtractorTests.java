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
}
