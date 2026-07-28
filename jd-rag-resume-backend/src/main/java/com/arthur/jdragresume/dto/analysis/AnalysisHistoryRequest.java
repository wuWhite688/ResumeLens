package com.arthur.jdragresume.dto.analysis;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotNull;

@JsonIgnoreProperties({"matchScore", "status"})
public record AnalysisHistoryRequest(
        @NotNull Long resumeId,
        @NotNull Long jobDescriptionId,
        String summary
) {
}
