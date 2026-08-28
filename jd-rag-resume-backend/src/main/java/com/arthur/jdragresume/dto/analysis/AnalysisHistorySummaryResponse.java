package com.arthur.jdragresume.dto.analysis;

import com.arthur.jdragresume.entity.AnalysisStatus;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record AnalysisHistorySummaryResponse(
        Long id,
        Long resumeId,
        Long jobDescriptionId,
        BigDecimal matchScore,
        AnalysisStatus status,
        LocalDateTime createdAt
) {
}
