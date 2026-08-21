package com.arthur.jdragresume.service;

import com.arthur.jdragresume.common.PageResponse;
import com.arthur.jdragresume.dto.job.JobDescriptionBulkImportRequest;
import com.arthur.jdragresume.dto.job.JobDescriptionRequest;
import com.arthur.jdragresume.dto.job.JobDescriptionResponse;
import com.arthur.jdragresume.entity.AppUser;
import com.arthur.jdragresume.entity.JobDescription;
import com.arthur.jdragresume.exception.ResourceNotFoundException;
import com.arthur.jdragresume.repository.JobDescriptionRepository;
import com.arthur.jdragresume.security.CurrentUserService;
import com.arthur.jdragresume.common.PageRequests;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class JobDescriptionService {
    private final JobDescriptionRepository jobDescriptionRepository;
    private final CurrentUserService currentUserService;

    public JobDescriptionService(
            JobDescriptionRepository jobDescriptionRepository,
            CurrentUserService currentUserService
    ) {
        this.jobDescriptionRepository = jobDescriptionRepository;
        this.currentUserService = currentUserService;
    }

    @Transactional(readOnly = true)
    public PageResponse<JobDescriptionResponse> findAll(int page, int size, String keyword) {
        AppUser user = currentUserService.getCurrentUser();
        String safeKeyword = normalizeKeyword(keyword);
        var pageRequest = PageRequests.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        return PageResponse.from(jobDescriptionRepository
                .findByUserIdAndTitleContainingIgnoreCaseOrUserIdAndCompanyNameContainingIgnoreCase(
                        user.getId(),
                        safeKeyword,
                        user.getId(),
                        safeKeyword,
                        pageRequest
                )
                .map(JobDescriptionResponse::from));
    }

    @Transactional(readOnly = true)
    public JobDescriptionResponse findById(Long id) {
        return JobDescriptionResponse.from(getEntityForCurrentUser(id));
    }

    @Transactional
    public JobDescriptionResponse create(JobDescriptionRequest request) {
        JobDescription jobDescription = new JobDescription();
        jobDescription.setUser(currentUserService.getCurrentUser());
        applyRequest(jobDescription, request);
        return JobDescriptionResponse.from(jobDescriptionRepository.save(jobDescription));
    }

    @Transactional
    public List<JobDescriptionResponse> bulkImport(JobDescriptionBulkImportRequest request) {
        AppUser user = currentUserService.getCurrentUser();
        return request.items().stream()
                .map(item -> {
                    JobDescription jobDescription = new JobDescription();
                    jobDescription.setUser(user);
                    applyRequest(jobDescription, item);
                    return jobDescriptionRepository.save(jobDescription);
                })
                .map(JobDescriptionResponse::from)
                .toList();
    }

    @Transactional
    public JobDescriptionResponse update(Long id, JobDescriptionRequest request) {
        JobDescription jobDescription = getEntityForCurrentUser(id);
        applyRequest(jobDescription, request);
        return JobDescriptionResponse.from(jobDescriptionRepository.save(jobDescription));
    }

    @Transactional
    public void delete(Long id) {
        JobDescription jobDescription = getEntityForCurrentUser(id);
        jobDescriptionRepository.delete(jobDescription);
    }

    @Transactional(readOnly = true)
    public JobDescription getEntityForCurrentUser(Long id) {
        AppUser user = currentUserService.getCurrentUser();
        return jobDescriptionRepository.findByIdAndUserId(id, user.getId())
                .orElseThrow(() -> new ResourceNotFoundException("job description", id));
    }

    private void applyRequest(JobDescription jobDescription, JobDescriptionRequest request) {
        jobDescription.setTitle(request.title());
        jobDescription.setCompanyName(request.companyName());
        jobDescription.setLocation(request.location());
        jobDescription.setEmploymentType(request.employmentType());
        jobDescription.setDescription(request.description());
        jobDescription.setRequirements(request.requirements());
    }

    private String normalizeKeyword(String keyword) {
        return keyword == null ? "" : keyword.trim();
    }
}
