package com.arthur.jdragresume.service;

import com.arthur.jdragresume.dto.job.JobSemanticMatchResponse;
import com.arthur.jdragresume.entity.AppUser;
import com.arthur.jdragresume.entity.JobDescription;
import com.arthur.jdragresume.entity.Resume;
import com.arthur.jdragresume.exception.BusinessException;
import com.arthur.jdragresume.exception.ResourceNotFoundException;
import com.arthur.jdragresume.repository.JobDescriptionRepository;
import com.arthur.jdragresume.repository.ResumeRepository;
import com.arthur.jdragresume.security.CurrentUserService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Service
public class JobSemanticMatchService {
    private static final int MAX_MATCHES = 200;

    private final JobDescriptionRepository jobDescriptionRepository;
    private final ResumeRepository resumeRepository;
    private final CurrentUserService currentUserService;
    private final SemanticEmbeddingService semanticEmbeddingService;

    public JobSemanticMatchService(
            JobDescriptionRepository jobDescriptionRepository,
            ResumeRepository resumeRepository,
            CurrentUserService currentUserService,
            SemanticEmbeddingService semanticEmbeddingService
    ) {
        this.jobDescriptionRepository = jobDescriptionRepository;
        this.resumeRepository = resumeRepository;
        this.currentUserService = currentUserService;
        this.semanticEmbeddingService = semanticEmbeddingService;
    }

    /**
     * Backfills legacy/stale vectors, then performs an in-memory cosine sort.
     * With the enforced 200-JD user quota this is intentionally simpler than a vector database.
     */
    @Transactional
    public List<JobSemanticMatchResponse> rank(Long resumeId, int limit) {
        AppUser user = currentUserService.getCurrentUser();
        Resume resume = resumeRepository.findByIdAndUserId(resumeId, user.getId())
                .orElseThrow(() -> new ResourceNotFoundException("resume", resumeId));
        if (resume.getRawText() == null || resume.getRawText().isBlank()) {
            throw new BusinessException("RESUME_TEXT_EMPTY", "resume rawText is empty");
        }

        if (!semanticEmbeddingService.isCurrent(resume)) {
            semanticEmbeddingService.refresh(resume);
            resumeRepository.save(resume);
        }

        List<JobDescription> jobs = jobDescriptionRepository.findAllByUserId(user.getId());
        List<JobDescription> staleJobs = new ArrayList<>();
        for (JobDescription job : jobs) {
            if (!semanticEmbeddingService.isCurrent(job)) {
                staleJobs.add(job);
            }
        }
        if (!staleJobs.isEmpty()) {
            semanticEmbeddingService.refreshJobs(staleJobs);
            jobDescriptionRepository.saveAll(staleJobs);
        }

        int safeLimit = Math.max(1, Math.min(MAX_MATCHES, limit));
        return jobs.stream()
                .map(job -> JobSemanticMatchResponse.from(
                        job,
                        semanticEmbeddingService.similarity(resume, job)
                ))
                .sorted(Comparator
                        .comparingDouble(JobSemanticMatchResponse::similarity)
                        .reversed()
                        .thenComparing(match -> match.job().id(), Comparator.reverseOrder()))
                .limit(safeLimit)
                .toList();
    }
}
