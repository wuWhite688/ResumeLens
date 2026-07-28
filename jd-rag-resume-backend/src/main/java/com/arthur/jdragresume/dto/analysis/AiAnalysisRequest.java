package com.arthur.jdragresume.dto.analysis;

import jakarta.validation.constraints.NotNull;

public record AiAnalysisRequest(
        @NotNull Long resumeId,
        @NotNull Long jobDescriptionId
) {
}
