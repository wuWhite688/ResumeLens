package com.arthur.jdragresume.service;

import com.arthur.jdragresume.dto.analysis.AiAnalysisResult;
import com.arthur.jdragresume.exception.BusinessException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AiAnalysisServiceParsingTests {
    private final AnalysisResultParser parser = new AnalysisResultParser(new ObjectMapper());

    @Test
    void parsesStrictJsonResponse() {
        AiAnalysisResult result = parser.parse("""
                {
                  "matchScore": 82.5,
                  "strengths": "Java and Spring Boot",
                  "missingSkills": "Deployment",
                  "improvementSuggestions": "Add delivery metrics",
                  "interviewQuestions": "Explain JWT",
                  "summary": "Good backend match"
                }
                """);

        assertEquals(new BigDecimal("82.5"), result.matchScore());
        assertEquals("Java and Spring Boot", result.strengths());
        assertEquals("Good backend match", result.summary());
    }

    @Test
    void normalizesPercentScoreArraysAndMarkdownFence() {
        AiAnalysisResult result = parser.parse("""
                ```json
                {
                  "matchScore": "75%",
                  "strengths": ["Java", "MySQL"],
                  "missingSkills": ["Testing", "Deployment"],
                  "improvementSuggestions": "Add evidence",
                  "interviewQuestions": ["Explain JWT", "Explain indexing"],
                  "summary": "Solid match"
                }
                ```
                """);

        assertEquals(new BigDecimal("75"), result.matchScore());
        assertEquals("Java\nMySQL", result.strengths());
        assertEquals("Testing\nDeployment", result.missingSkills());
        assertEquals("Explain JWT\nExplain indexing", result.interviewQuestions());
    }

    @Test
    void rejectsResponseWithoutScore() {
        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> parser.parse("{\"summary\":\"missing score\"}")
        );

        assertEquals("AI_RESPONSE_PARSE_FAILED", exception.getCode());
    }
}
