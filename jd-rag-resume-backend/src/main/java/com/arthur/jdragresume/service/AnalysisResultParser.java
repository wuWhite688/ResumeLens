package com.arthur.jdragresume.service;

import com.arthur.jdragresume.dto.analysis.AiAnalysisResult;
import com.arthur.jdragresume.exception.BusinessException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.StringJoiner;

@Component
public class AnalysisResultParser {
    private final ObjectMapper objectMapper;

    public AnalysisResultParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    AiAnalysisResult parse(String text) {
        try {
            JsonNode root = objectMapper.readTree(stripMarkdownFence(text));
            return new AiAnalysisResult(parseScore(root.path("matchScore")), normalize(root.path("strengths")),
                    normalize(root.path("missingSkills")), normalize(root.path("improvementSuggestions")),
                    normalize(root.path("interviewQuestions")), normalize(root.path("summary")));
        } catch (Exception ex) {
            throw new BusinessException("AI_RESPONSE_PARSE_FAILED", "AI response is not valid analysis JSON");
        }
    }

    private BigDecimal parseScore(JsonNode node) {
        if (node.isNumber()) return node.decimalValue();
        String value = node.asText("").replace("%", "").trim();
        if (value.isEmpty()) throw new IllegalArgumentException("matchScore is missing");
        return new BigDecimal(value);
    }

    private String normalize(JsonNode node) {
        if (node.isMissingNode() || node.isNull()) return "";
        if (node.isTextual()) return node.asText();
        if (node.isArray()) {
            StringJoiner joiner = new StringJoiner("\n");
            node.forEach(item -> joiner.add(item.isTextual() ? item.asText() : item.toString()));
            return joiner.toString();
        }
        return node.toString();
    }

    private String stripMarkdownFence(String text) {
        String trimmed = text.trim();
        if (!trimmed.startsWith("```")) return trimmed;
        return trimmed.replaceFirst("(?is)^```(?:json)?\\s*", "")
                .replaceFirst("(?is)\\s*```$", "").trim();
    }
}
