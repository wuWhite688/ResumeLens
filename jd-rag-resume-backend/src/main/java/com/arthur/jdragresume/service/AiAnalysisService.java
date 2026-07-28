package com.arthur.jdragresume.service;

import com.arthur.jdragresume.dto.analysis.AiAnalysisRequest;
import com.arthur.jdragresume.dto.analysis.AnalysisHistoryResponse;
import com.arthur.jdragresume.entity.AnalysisHistory;
import com.arthur.jdragresume.entity.AnalysisStatus;
import com.arthur.jdragresume.entity.AppUser;
import com.arthur.jdragresume.entity.JobDescription;
import com.arthur.jdragresume.entity.Resume;
import com.arthur.jdragresume.exception.BusinessException;
import com.arthur.jdragresume.repository.AnalysisHistoryRepository;
import com.arthur.jdragresume.security.CurrentUserService;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;

@Service
public class AiAnalysisService {
    private final CurrentUserService currentUserService;
    private final ResumeService resumeService;
    private final JobDescriptionService jobDescriptionService;
    private final AnalysisHistoryRepository analysisHistoryRepository;
    private final AiAnalysisWorker aiAnalysisWorker;
    private final TaskExecutor analysisTaskExecutor;

    public AiAnalysisService(
            CurrentUserService currentUserService,
            ResumeService resumeService,
            JobDescriptionService jobDescriptionService,
            AnalysisHistoryRepository analysisHistoryRepository,
            AiAnalysisWorker aiAnalysisWorker,
            @Qualifier("analysisTaskExecutor") TaskExecutor analysisTaskExecutor
    ) {
        this.currentUserService = currentUserService;
        this.resumeService = resumeService;
        this.jobDescriptionService = jobDescriptionService;
        this.analysisHistoryRepository = analysisHistoryRepository;
        this.aiAnalysisWorker = aiAnalysisWorker;
        this.analysisTaskExecutor = analysisTaskExecutor;
    }

    public AnalysisHistoryResponse analyze(AiAnalysisRequest request) {
        AppUser user = currentUserService.getCurrentUser();
        Resume resume = resumeService.getEntityForCurrentUser(request.resumeId());
        JobDescription jobDescription = jobDescriptionService.getEntityForCurrentUser(request.jobDescriptionId());
        if (resume.getRawText() == null || resume.getRawText().isBlank()) {
            throw new BusinessException("RESUME_TEXT_EMPTY", "resume rawText is empty");
        }

        AnalysisHistory history = new AnalysisHistory();
        history.setUser(user);
        history.setResume(resume);
        history.setJobDescription(jobDescription);
        history.setStatus(AnalysisStatus.PENDING);
        history.setSummary("AI analysis is pending");
        AnalysisHistory saved = analysisHistoryRepository.save(history);
        try {
            analysisTaskExecutor.execute(() -> aiAnalysisWorker.process(saved.getId()));
        } catch (RuntimeException ex) {
            saved.setStatus(AnalysisStatus.FAILED);
            saved.setSummary("AI analysis failed: task queue is full");
            analysisHistoryRepository.save(saved);
            throw new BusinessException("ANALYSIS_QUEUE_FULL", "AI analysis task queue is full, please retry later");
        }
        return AnalysisHistoryResponse.from(saved);
    }
}
