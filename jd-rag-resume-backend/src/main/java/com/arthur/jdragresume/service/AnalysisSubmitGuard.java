package com.arthur.jdragresume.service;

import com.arthur.jdragresume.entity.AnalysisHistory;
import com.arthur.jdragresume.entity.AnalysisStatus;
import com.arthur.jdragresume.entity.AppUser;
import com.arthur.jdragresume.entity.JobDescription;
import com.arthur.jdragresume.entity.Resume;
import com.arthur.jdragresume.exception.BusinessException;
import com.arthur.jdragresume.repository.AnalysisHistoryRepository;
import com.arthur.jdragresume.repository.AppUserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
public class AnalysisSubmitGuard {
    private final AnalysisHistoryRepository analysisHistoryRepository;
    private final AppUserRepository appUserRepository;
    private final int maxPendingPerUser;
    private final int maxSubmitsPerWindow;
    private final long submitWindowMinutes;

    public AnalysisSubmitGuard(
            AnalysisHistoryRepository analysisHistoryRepository,
            AppUserRepository appUserRepository,
            @Value("${app.analysis.max-pending-per-user:2}") int maxPendingPerUser,
            @Value("${app.analysis.max-submits-per-window:10}") int maxSubmitsPerWindow,
            @Value("${app.analysis.submit-window-minutes:10}") long submitWindowMinutes
    ) {
        this.analysisHistoryRepository = analysisHistoryRepository;
        this.appUserRepository = appUserRepository;
        this.maxPendingPerUser = Math.max(1, maxPendingPerUser);
        this.maxSubmitsPerWindow = Math.max(1, maxSubmitsPerWindow);
        this.submitWindowMinutes = Math.max(1, submitWindowMinutes);
    }

    @Transactional
    public AnalysisHistory admit(AppUser user, Resume resume, JobDescription jobDescription) {
        AppUser lockedUser = appUserRepository.findByIdForUpdate(user.getId())
                .orElseThrow(() -> new IllegalStateException("current user no longer exists"));

        if (analysisHistoryRepository.existsByUser_IdAndResume_IdAndJobDescription_IdAndStatus(
                lockedUser.getId(),
                resume.getId(),
                jobDescription.getId(),
                AnalysisStatus.PENDING
        )) {
            throw new BusinessException(
                    "ANALYSIS_ALREADY_PENDING",
                    "an analysis for this resume and job is already running"
            );
        }

        long pending = analysisHistoryRepository.countByUser_IdAndStatus(
                lockedUser.getId(),
                AnalysisStatus.PENDING
        );
        if (pending >= maxPendingPerUser) {
            throw new BusinessException(
                    "ANALYSIS_TOO_MANY_PENDING",
                    "too many analyses are already running, please wait for one to finish"
            );
        }

        LocalDateTime cutoff = LocalDateTime.now().minusMinutes(submitWindowMinutes);
        long submitted = analysisHistoryRepository.countByUser_IdAndCreatedAtAfter(lockedUser.getId(), cutoff);
        if (submitted >= maxSubmitsPerWindow) {
            throw new BusinessException(
                    "ANALYSIS_RATE_LIMITED",
                    "too many analysis requests, please retry later"
            );
        }

        AnalysisHistory history = new AnalysisHistory();
        history.setUser(lockedUser);
        history.setResume(resume);
        history.setJobDescription(jobDescription);
        history.setStatus(AnalysisStatus.PENDING);
        history.setSummary("AI analysis is pending");
        return analysisHistoryRepository.save(history);
    }
}
