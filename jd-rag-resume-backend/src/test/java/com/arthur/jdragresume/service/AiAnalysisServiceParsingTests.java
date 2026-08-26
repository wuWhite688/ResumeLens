package com.arthur.jdragresume.service;

import com.arthur.jdragresume.dto.analysis.AiAnalysisResult;
import com.arthur.jdragresume.exception.BusinessException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AiAnalysisServiceParsingTests {
    private final AnalysisResultParser parser = new AnalysisResultParser(new ObjectMapper());

    @Test
    void parsesStrictJsonResponse() {
        AiAnalysisResult result = parser.parse("""
                {
                  "matchScore": 82.5,
                  "strengths": "Java and Spring Boot [chunk-0]",
                  "missingSkills": "Deployment",
                  "improvementSuggestions": "Add delivery metrics",
                  "interviewQuestions": "Explain JWT",
                  "summary": "Good backend match"
                }
                """, Set.of(0));

        assertEquals(new BigDecimal("82.5"), result.matchScore());
        assertEquals("Java and Spring Boot [chunk-0]", result.strengths());
        assertEquals("Good backend match", result.summary());
    }

    @Test
    void normalizesPercentScoreArraysAndMarkdownFence() {
        AiAnalysisResult result = parser.parse("""
                ```json
                {
                  "matchScore": "75%",
                  "strengths": ["Java [chunk-0]", "MySQL [chunk-1]"],
                  "missingSkills": ["Testing", "Deployment"],
                  "improvementSuggestions": "Add evidence",
                  "interviewQuestions": ["Explain JWT", "Explain indexing"],
                  "summary": "Solid match"
                }
                ```
                """, Set.of(0, 1));

        assertEquals(new BigDecimal("75"), result.matchScore());
        assertEquals("Java [chunk-0]\nMySQL [chunk-1]", result.strengths());
        assertEquals("Testing\nDeployment", result.missingSkills());
        assertEquals("Explain JWT\nExplain indexing", result.interviewQuestions());
    }

    @Test
    void rejectsResponseWithoutScore() {
        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> parser.parse("{\"summary\":\"missing score\"}", Set.of())
        );

        assertEquals("AI_RESPONSE_PARSE_FAILED", exception.getCode());
    }

    @Test
    void rejectsStrengthWithoutCitation() {
        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> parser.parse(responseWithStrengths("Java and Spring Boot"), Set.of(0))
        );

        assertEquals("AI_RESPONSE_CITATION_INVALID", exception.getCode());
    }

    @Test
    void rejectsCitationToFilteredOrMissingChunk() {
        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> parser.parse(responseWithStrengths("Java [chunk-2]"), Set.of(0, 1))
        );

        assertEquals("AI_RESPONSE_CITATION_INVALID", exception.getCode());
    }

    @Test
    void rejectsMalformedCitationEvenWhenAnotherCitationIsValid() {
        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> parser.parse(responseWithStrengths("Java [chunk-0] [chunk-x]"), Set.of(0))
        );

        assertEquals("AI_RESPONSE_CITATION_INVALID", exception.getCode());
    }

    private String responseWithStrengths(String strengths) {
        return """
                {
                  "matchScore": 80,
                  "strengths": "%s",
                  "missingSkills": "",
                  "improvementSuggestions": "",
                  "interviewQuestions": "",
                  "summary": "summary"
                }
                """.formatted(strengths);
    }
}
