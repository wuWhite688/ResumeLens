package com.arthur.jdragresume.service;

import com.arthur.jdragresume.dto.analysis.AiAnalysisRequest;
import com.arthur.jdragresume.dto.analysis.AnalysisHistoryResponse;
import com.arthur.jdragresume.dto.browser.BrowserExtensionAnalyzeRequest;
import com.arthur.jdragresume.dto.browser.BrowserExtensionAnalyzeResponse;
import com.arthur.jdragresume.dto.job.JobCaptureResponse;
import com.arthur.jdragresume.entity.AnalysisStatus;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
public class BrowserExtensionService {
    private final JobDescriptionService jobDescriptionService;
    private final AnalysisHistoryService analysisHistoryService;
    private final AiAnalysisService aiAnalysisService;

    public BrowserExtensionService(
            JobDescriptionService jobDescriptionService,
            AnalysisHistoryService analysisHistoryService,
            AiAnalysisService aiAnalysisService
    ) {
        this.jobDescriptionService = jobDescriptionService;
        this.analysisHistoryService = analysisHistoryService;
        this.aiAnalysisService = aiAnalysisService;
    }

    public BrowserExtensionAnalyzeResponse analyze(BrowserExtensionAnalyzeRequest request) {
        JobCaptureResponse captured = jobDescriptionService.capture(request.job());
        Long jobId = captured.job().id();

        Optional<AnalysisHistoryResponse> latest = captured.existingJob()
                ? analysisHistoryService.findLatest(request.resumeId(), jobId)
                : Optional.empty();
        if (!request.forceReanalyze() && latest.isPresent()) {
            AnalysisHistoryResponse previous = latest.get();
            boolean reusablePending = previous.status() == AnalysisStatus.PENDING;
            boolean reusableCompleted = previous.status() == AnalysisStatus.COMPLETED
                    && !captured.contentChanged();
            if (reusablePending || reusableCompleted) {
                return response(captured, previous, true);
            }
        }

        AnalysisHistoryResponse analysis = aiAnalysisService.analyze(
                new AiAnalysisRequest(request.resumeId(), jobId)
        );
        return response(captured, analysis, false);
    }

    private BrowserExtensionAnalyzeResponse response(
            JobCaptureResponse captured,
            AnalysisHistoryResponse analysis,
            boolean reusedAnalysis
    ) {
        return new BrowserExtensionAnalyzeResponse(
                captured.job(),
                analysis,
                captured.existingJob(),
                captured.contentChanged(),
                reusedAnalysis
        );
    }
}
