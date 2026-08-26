package com.arthur.jdragresume.dto.browser;

import com.arthur.jdragresume.dto.job.JobCaptureRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

public record BrowserExtensionAnalyzeRequest(
        @NotNull Long resumeId,
        @NotNull @Valid JobCaptureRequest job,
        boolean forceReanalyze
) {
}
