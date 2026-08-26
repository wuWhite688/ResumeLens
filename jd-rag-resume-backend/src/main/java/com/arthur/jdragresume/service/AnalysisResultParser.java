package com.arthur.jdragresume.service;

import com.arthur.jdragresume.dto.analysis.AiAnalysisResult;
import com.arthur.jdragresume.exception.BusinessException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Set;
import java.util.StringJoiner;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class AnalysisResultParser {
    private static final Pattern CITATION_PATTERN = Pattern.compile("\\[chunk-(\\d+)]");
    private final ObjectMapper objectMapper;

    public AnalysisResultParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    AiAnalysisResult parse(String text, Set<Integer> keptChunkIndexes) {
        AiAnalysisResult result;
        try {
            JsonNode root = objectMapper.readTree(stripMarkdownFence(text));
            result = new AiAnalysisResult(parseScore(root.path("matchScore")), normalize(root.path("strengths")),
                    normalize(root.path("missingSkills")), normalize(root.path("improvementSuggestions")),
                    normalize(root.path("interviewQuestions")), normalize(root.path("summary")));
        } catch (Exception ex) {
            throw new BusinessException("AI_RESPONSE_PARSE_FAILED", "AI response is not valid analysis JSON");
        }
        validateStrengthCitations(result.strengths(), keptChunkIndexes);
        return result;
    }

    private void validateStrengthCitations(String strengths, Set<Integer> keptChunkIndexes) {
        if (strengths == null || strengths.isBlank()) {
            return;
        }
        Set<Integer> allowed = keptChunkIndexes == null ? Set.of() : Set.copyOf(keptChunkIndexes);
        for (String item : strengths.split("\\R|[；;]")) {
            if (item.isBlank()) {
                continue;
            }
            Matcher matcher = CITATION_PATTERN.matcher(item);
            boolean found = false;
            while (matcher.find()) {
                found = true;
                int chunkIndex;
                try {
                    chunkIndex = Integer.parseInt(matcher.group(1));
                } catch (NumberFormatException ex) {
                    throw invalidCitation();
                }
                if (!allowed.contains(chunkIndex)) {
                    throw invalidCitation();
                }
            }
            if (!found || matcher.replaceAll("").contains("[chunk-")) {
                throw invalidCitation();
            }
        }
    }

    private BusinessException invalidCitation() {
        return new BusinessException(
                "AI_RESPONSE_CITATION_INVALID",
                "each strength must cite only kept resume chunks"
        );
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
