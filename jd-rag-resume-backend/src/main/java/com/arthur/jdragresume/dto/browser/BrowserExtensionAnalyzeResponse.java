package com.arthur.jdragresume.dto.browser;

import com.arthur.jdragresume.dto.analysis.AnalysisHistoryResponse;
import com.arthur.jdragresume.dto.job.JobDescriptionResponse;

public record BrowserExtensionAnalyzeResponse(
        JobDescriptionResponse job,
        AnalysisHistoryResponse analysis,
        boolean existingJob,
        boolean contentChanged,
        boolean reusedAnalysis
) {
}
