package com.arthur.jdragresume.service;

import com.arthur.jdragresume.common.PageResponse;
import com.arthur.jdragresume.dto.analysis.AnalysisHistoryRequest;
import com.arthur.jdragresume.dto.analysis.AnalysisHistoryResponse;
import com.arthur.jdragresume.dto.analysis.AnalysisHistorySummaryResponse;
import com.arthur.jdragresume.entity.AnalysisHistory;
import com.arthur.jdragresume.entity.AnalysisStatus;
import com.arthur.jdragresume.entity.AppUser;
import com.arthur.jdragresume.entity.JobDescription;
import com.arthur.jdragresume.entity.Resume;
import com.arthur.jdragresume.exception.BusinessException;
import com.arthur.jdragresume.exception.ResourceNotFoundException;
import com.arthur.jdragresume.repository.AnalysisHistoryRepository;
import com.arthur.jdragresume.security.CurrentUserService;
import com.arthur.jdragresume.common.PageRequests;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.List;

@Service
public class AnalysisHistoryService {
    private final AnalysisHistoryRepository analysisHistoryRepository;
    private final CurrentUserService currentUserService;
    private final ResumeService resumeService;
    private final JobDescriptionService jobDescriptionService;

    public AnalysisHistoryService(
            AnalysisHistoryRepository analysisHistoryRepository,
            CurrentUserService currentUserService,
            ResumeService resumeService,
            JobDescriptionService jobDescriptionService
    ) {
        this.analysisHistoryRepository = analysisHistoryRepository;
        this.currentUserService = currentUserService;
        this.resumeService = resumeService;
        this.jobDescriptionService = jobDescriptionService;
    }

    @Transactional(readOnly = true)
    public PageResponse<AnalysisHistoryResponse> findAll(int page, int size, String keyword) {
        AppUser user = currentUserService.getCurrentUser();
        var pageRequest = PageRequests.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        return PageResponse.from(analysisHistoryRepository
                .searchByUserId(user.getId(), normalizeKeyword(keyword), pageRequest)
                .map(AnalysisHistoryResponse::from));
    }

    @Transactional(readOnly = true)
    public AnalysisHistoryResponse findById(Long id) {
        return AnalysisHistoryResponse.from(getEntityForCurrentUser(id));
    }

    @Transactional(readOnly = true)
    public Optional<AnalysisHistoryResponse> findLatest(Long resumeId, Long jobDescriptionId) {
        AppUser user = currentUserService.getCurrentUser();
        Resume resume = resumeService.getEntityForCurrentUser(resumeId);
        JobDescription jobDescription = jobDescriptionService.getEntityForCurrentUser(jobDescriptionId);
        return analysisHistoryRepository
                .findFirstByUser_IdAndResume_IdAndJobDescription_IdAndResumeFingerprintAndJobFingerprintOrderByIdDesc(
                        user.getId(),
                        resumeId,
                        jobDescriptionId,
                        ContentFingerprints.resume(resume),
                        ContentFingerprints.job(jobDescription)
                )
                .map(AnalysisHistoryResponse::from);
    }

    @Transactional(readOnly = true)
    public List<AnalysisHistorySummaryResponse> findLatestForEachJob(Long resumeId) {
        AppUser user = currentUserService.getCurrentUser();
        Resume resume = resumeService.getEntityForCurrentUser(resumeId);
        return analysisHistoryRepository
                .findLatestCurrentForEachJobByUserIdAndResumeId(
                        user.getId(),
                        resumeId,
                        ContentFingerprints.resume(resume)
                );
    }

    @Transactional
    public AnalysisHistoryResponse create(AnalysisHistoryRequest request) {
        AnalysisHistory history = new AnalysisHistory();
        history.setStatus(AnalysisStatus.PENDING);
        applyRequest(history, request);
        return AnalysisHistoryResponse.from(analysisHistoryRepository.save(history));
    }

    @Transactional
    public AnalysisHistoryResponse update(Long id, AnalysisHistoryRequest request) {
        AnalysisHistory history = getEntityForCurrentUser(id);
        if (!history.getResume().getId().equals(request.resumeId())
                || !history.getJobDescription().getId().equals(request.jobDescriptionId())) {
            throw new BusinessException(
                    "ANALYSIS_INPUT_IMMUTABLE",
                    "analysis resume and job cannot be changed after submission"
            );
        }
        history.setSummary(request.summary());
        return AnalysisHistoryResponse.from(analysisHistoryRepository.save(history));
    }

    @Transactional
    public void delete(Long id) {
        AnalysisHistory history = getEntityForCurrentUser(id);
        analysisHistoryRepository.delete(history);
    }

    @Transactional(readOnly = true)
    public AnalysisHistory getEntityForCurrentUser(Long id) {
        AppUser user = currentUserService.getCurrentUser();
        return analysisHistoryRepository.findByIdAndUserId(id, user.getId())
                .orElseThrow(() -> new ResourceNotFoundException("analysis history", id));
    }

    private void applyRequest(AnalysisHistory history, AnalysisHistoryRequest request) {
        AppUser user = currentUserService.getCurrentUser();
        Resume resume = resumeService.getEntityForCurrentUser(request.resumeId());
        JobDescription jobDescription = jobDescriptionService.getEntityForCurrentUser(request.jobDescriptionId());

        history.setUser(user);
        history.setResume(resume);
        history.setJobDescription(jobDescription);
        history.setResumeFingerprint(ContentFingerprints.resume(resume));
        String jobFingerprint = ContentFingerprints.job(jobDescription);
        jobDescription.setContentFingerprint(jobFingerprint);
        history.setJobFingerprint(jobFingerprint);
        history.setSummary(request.summary());
    }

    private String normalizeKeyword(String keyword) {
        return keyword == null ? "" : keyword.trim();
    }
}
