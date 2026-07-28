package com.arthur.jdragresume.dto.analysis;

import com.arthur.jdragresume.entity.AnalysisStatus;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

public record AnalysisHistoryRequest(
        @NotNull Long resumeId,
        @NotNull Long jobDescriptionId,
        @DecimalMin("0.00") @DecimalMax("100.00") BigDecimal matchScore,
        AnalysisStatus status,
        String summary
) {
}
