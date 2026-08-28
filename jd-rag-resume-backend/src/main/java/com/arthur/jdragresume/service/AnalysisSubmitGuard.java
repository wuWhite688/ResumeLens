package com.arthur.jdragresume.service;

import com.arthur.jdragresume.entity.AnalysisHistory;
import com.arthur.jdragresume.entity.AnalysisStatus;
import com.arthur.jdragresume.entity.AppUser;
import com.arthur.jdragresume.entity.JobDescription;
import com.arthur.jdragresume.entity.Resume;
import com.arthur.jdragresume.exception.BusinessException;
import com.arthur.jdragresume.repository.AnalysisHistoryRepository;
import com.arthur.jdragresume.repository.AppUserRepository;
import com.arthur.jdragresume.repository.JobDescriptionRepository;
import com.arthur.jdragresume.repository.ResumeRepository;
import com.arthur.jdragresume.exception.ResourceNotFoundException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
public class AnalysisSubmitGuard {
    private final AnalysisHistoryRepository analysisHistoryRepository;
    private final AppUserRepository appUserRepository;
    private final ResumeRepository resumeRepository;
    private final JobDescriptionRepository jobDescriptionRepository;
    private final int maxPendingPerUser;
    private final int maxSubmitsPerWindow;
    private final long submitWindowMinutes;

    public AnalysisSubmitGuard(
            AnalysisHistoryRepository analysisHistoryRepository,
            AppUserRepository appUserRepository,
            ResumeRepository resumeRepository,
            JobDescriptionRepository jobDescriptionRepository,
            @Value("${app.analysis.max-pending-per-user:2}") int maxPendingPerUser,
            @Value("${app.analysis.max-submits-per-window:10}") int maxSubmitsPerWindow,
            @Value("${app.analysis.submit-window-minutes:10}") long submitWindowMinutes
    ) {
        this.analysisHistoryRepository = analysisHistoryRepository;
        this.appUserRepository = appUserRepository;
        this.resumeRepository = resumeRepository;
        this.jobDescriptionRepository = jobDescriptionRepository;
        this.maxPendingPerUser = Math.max(1, maxPendingPerUser);
        this.maxSubmitsPerWindow = Math.max(1, maxSubmitsPerWindow);
        this.submitWindowMinutes = Math.max(1, submitWindowMinutes);
    }

    @Transactional
    public Admission admit(AppUser user, Long resumeId, Long jobDescriptionId) {
        AppUser lockedUser = appUserRepository.findByIdForUpdate(user.getId())
                .orElseThrow(() -> new IllegalStateException("current user no longer exists"));
        Resume resume = resumeRepository.findByIdAndUserId(resumeId, lockedUser.getId())
                .orElseThrow(() -> new ResourceNotFoundException("resume", resumeId));
        JobDescription jobDescription = jobDescriptionRepository
                .findByIdAndUserId(jobDescriptionId, lockedUser.getId())
                .orElseThrow(() -> new ResourceNotFoundException("job description", jobDescriptionId));
        if (resume.getRawText() == null || resume.getRawText().isBlank()) {
            throw new BusinessException("RESUME_TEXT_EMPTY", "resume rawText is empty");
        }

        String resumeFingerprint = ContentFingerprints.resume(resume);
        String jobFingerprint = ContentFingerprints.job(jobDescription);
        if (!jobFingerprint.equals(jobDescription.getContentFingerprint())) {
            jobDescription.setContentFingerprint(jobFingerprint);
        }

        var existingPending = analysisHistoryRepository
                .findFirstByUser_IdAndResume_IdAndJobDescription_IdAndStatusAndResumeFingerprintAndJobFingerprintOrderByIdDesc(
                        lockedUser.getId(),
                        resume.getId(),
                        jobDescription.getId(),
                        AnalysisStatus.PENDING,
                        resumeFingerprint,
                        jobFingerprint
                );
        if (existingPending.isPresent()) {
            return new Admission(existingPending.get(), true);
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
        history.setResumeFingerprint(resumeFingerprint);
        history.setJobFingerprint(jobFingerprint);
        history.setStatus(AnalysisStatus.PENDING);
        history.setSummary("AI analysis is pending");
        return new Admission(analysisHistoryRepository.save(history), false);
    }

    public record Admission(AnalysisHistory history, boolean reusedPending) {
    }
}
