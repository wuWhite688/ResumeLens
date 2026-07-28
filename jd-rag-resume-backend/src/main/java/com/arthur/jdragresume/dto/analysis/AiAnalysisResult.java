package com.arthur.jdragresume.dto.analysis;

import java.math.BigDecimal;

public record AiAnalysisResult(
        BigDecimal matchScore,
        String strengths,
        String missingSkills,
        String improvementSuggestions,
        String interviewQuestions,
        String summary
) {
}
