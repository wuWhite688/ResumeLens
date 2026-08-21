package com.arthur.jdragresume.service;

import com.arthur.jdragresume.common.PageResponse;
import com.arthur.jdragresume.dto.job.JobDescriptionBulkImportRequest;
import com.arthur.jdragresume.dto.job.JobDescriptionRequest;
import com.arthur.jdragresume.dto.job.JobDescriptionResponse;
import com.arthur.jdragresume.entity.AppUser;
import com.arthur.jdragresume.entity.JobDescription;
import com.arthur.jdragresume.exception.BusinessException;
import com.arthur.jdragresume.exception.ResourceNotFoundException;
import com.arthur.jdragresume.repository.AppUserRepository;
import com.arthur.jdragresume.repository.JobDescriptionRepository;
import com.arthur.jdragresume.security.CurrentUserService;
import com.arthur.jdragresume.common.PageRequests;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class JobDescriptionService {
    private final JobDescriptionRepository jobDescriptionRepository;
    private final CurrentUserService currentUserService;
    private final AppUserRepository appUserRepository;
    private final int maxJobDescriptionsPerUser;

    public JobDescriptionService(
            JobDescriptionRepository jobDescriptionRepository,
            CurrentUserService currentUserService,
            AppUserRepository appUserRepository,
            @Value("${app.job-description.max-per-user:200}") int maxJobDescriptionsPerUser
    ) {
        this.jobDescriptionRepository = jobDescriptionRepository;
        this.currentUserService = currentUserService;
        this.appUserRepository = appUserRepository;
        this.maxJobDescriptionsPerUser = Math.max(1, maxJobDescriptionsPerUser);
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
        AppUser user = lockCurrentUser();
        enforceJobDescriptionCount(user, 1);
        JobDescription jobDescription = new JobDescription();
        jobDescription.setUser(user);
        applyRequest(jobDescription, request);
        return JobDescriptionResponse.from(jobDescriptionRepository.save(jobDescription));
    }

    @Transactional
    public List<JobDescriptionResponse> bulkImport(JobDescriptionBulkImportRequest request) {
        AppUser user = lockCurrentUser();
        enforceJobDescriptionCount(user, request.items().size());
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

    /**
     * 与 ResumeService 一样先锁住用户行再计数：否则两个并发的 /import
     * 会各自读到配额用尽前的旧计数，双双通过校验后把上限顶穿。
     */
    private AppUser lockCurrentUser() {
        AppUser user = currentUserService.getCurrentUser();
        return appUserRepository.findByIdForUpdate(user.getId())
                .orElseThrow(() -> new IllegalStateException("current user no longer exists"));
    }

    /**
     * 简历侧有份数与字节数双重配额，岗位侧此前一份都没有：单条 JD 上限约 4 万字符，
     * /import 一次 50 条且可无限次调用，任何登录用户都能把存储撑满。
     */
    private void enforceJobDescriptionCount(AppUser user, int incoming) {
        long existing = jobDescriptionRepository.countByUserId(user.getId());
        if (existing + incoming > maxJobDescriptionsPerUser) {
            throw new BusinessException(
                    "JOB_DESCRIPTION_QUOTA_EXCEEDED",
                    "job description count exceeds the maximum of " + maxJobDescriptionsPerUser
            );
        }
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
