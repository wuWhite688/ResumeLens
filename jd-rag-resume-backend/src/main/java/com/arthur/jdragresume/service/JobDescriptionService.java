package com.arthur.jdragresume.service;

import com.arthur.jdragresume.common.PageResponse;
import com.arthur.jdragresume.dto.job.JobDescriptionBulkImportRequest;
import com.arthur.jdragresume.dto.job.JobCaptureRequest;
import com.arthur.jdragresume.dto.job.JobCaptureResponse;
import com.arthur.jdragresume.dto.job.JobDescriptionRequest;
import com.arthur.jdragresume.dto.job.JobDescriptionResponse;
import com.arthur.jdragresume.dto.job.JobSourceLookupResponse;
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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

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

    @Transactional(readOnly = true)
    public JobSourceLookupResponse findBySource(String sourcePlatform, String sourceJobId) {
        AppUser user = currentUserService.getCurrentUser();
        return jobDescriptionRepository.findByUserIdAndSourcePlatformAndSourceJobId(
                        user.getId(),
                        normalizeSourcePlatform(sourcePlatform),
                        normalizeSourceJobId(sourceJobId)
                )
                .map(JobDescriptionResponse::from)
                .map(JobSourceLookupResponse::found)
                .orElseGet(JobSourceLookupResponse::missing);
    }

    @Transactional
    public JobCaptureResponse capture(JobCaptureRequest request) {
        AppUser user = lockCurrentUser();
        String sourcePlatform = normalizeSourcePlatform(request.sourcePlatform());
        String sourceJobId = normalizeSourceJobId(request.sourceJobId());
        String fingerprint = contentFingerprint(request);
        LocalDateTime now = LocalDateTime.now();

        var existing = jobDescriptionRepository.findByUserIdAndSourcePlatformAndSourceJobId(
                user.getId(),
                sourcePlatform,
                sourceJobId
        );
        if (existing.isPresent()) {
            JobDescription jobDescription = existing.get();
            boolean contentChanged = !fingerprint.equals(jobDescription.getContentFingerprint());
            if (contentChanged) {
                applyCaptureRequest(jobDescription, request);
                jobDescription.setContentFingerprint(fingerprint);
            }
            jobDescription.setSourceUrl(request.sourceUrl().trim());
            jobDescription.setLastSeenAt(now);
            JobDescription saved = jobDescriptionRepository.save(jobDescription);
            return new JobCaptureResponse(JobDescriptionResponse.from(saved), true, contentChanged);
        }

        enforceJobDescriptionCount(user, 1);
        JobDescription jobDescription = new JobDescription();
        jobDescription.setUser(user);
        applyCaptureRequest(jobDescription, request);
        jobDescription.setSourcePlatform(sourcePlatform);
        jobDescription.setSourceUrl(request.sourceUrl().trim());
        jobDescription.setSourceJobId(sourceJobId);
        jobDescription.setContentFingerprint(fingerprint);
        jobDescription.setLastSeenAt(now);
        JobDescription saved = jobDescriptionRepository.save(jobDescription);
        return new JobCaptureResponse(JobDescriptionResponse.from(saved), false, false);
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

    private void applyCaptureRequest(JobDescription jobDescription, JobCaptureRequest request) {
        jobDescription.setTitle(request.title().trim());
        jobDescription.setCompanyName(request.companyName().trim());
        jobDescription.setLocation(trimToNull(request.location()));
        jobDescription.setEmploymentType(trimToNull(request.employmentType()));
        jobDescription.setDescription(request.description().trim());
        jobDescription.setRequirements(trimToNull(request.requirements()));
    }

    private String normalizeSourcePlatform(String value) {
        String normalized = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        if (normalized.isBlank() || normalized.length() > 32) {
            throw new IllegalArgumentException("sourcePlatform must contain 1 to 32 characters");
        }
        return normalized;
    }

    private String normalizeSourceJobId(String value) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isBlank() || normalized.length() > 160) {
            throw new IllegalArgumentException("sourceJobId must contain 1 to 160 characters");
        }
        return normalized;
    }

    private String contentFingerprint(JobCaptureRequest request) {
        String canonical = String.join("\n",
                fingerprintPart(request.title()),
                fingerprintPart(request.companyName()),
                fingerprintPart(request.location()),
                fingerprintPart(request.employmentType()),
                fingerprintPart(request.description()),
                fingerprintPart(request.requirements())
        );
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }

    private String fingerprintPart(String value) {
        return value == null ? "" : value.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    private String trimToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private String normalizeKeyword(String keyword) {
        return keyword == null ? "" : keyword.trim();
    }
}
