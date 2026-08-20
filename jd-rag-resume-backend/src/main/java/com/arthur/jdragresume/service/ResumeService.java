package com.arthur.jdragresume.service;

import com.arthur.jdragresume.common.PageResponse;
import com.arthur.jdragresume.dto.resume.ResumeRequest;
import com.arthur.jdragresume.dto.resume.ResumeResponse;
import com.arthur.jdragresume.entity.AppUser;
import com.arthur.jdragresume.entity.Resume;
import com.arthur.jdragresume.exception.BusinessException;
import com.arthur.jdragresume.exception.ResourceNotFoundException;
import com.arthur.jdragresume.repository.ResumeRepository;
import com.arthur.jdragresume.repository.ResumeChunkRepository;
import com.arthur.jdragresume.rag.LuceneVectorIndex;
import com.arthur.jdragresume.security.CurrentUserService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Service
public class ResumeService {
    private static final Logger log = LoggerFactory.getLogger(ResumeService.class);
    private static final Set<String> ALLOWED_EXTENSIONS = Set.of("pdf", "doc", "docx", "txt", "md");

    private final ResumeRepository resumeRepository;
    private final ResumeChunkRepository resumeChunkRepository;
    private final CurrentUserService currentUserService;
    private final ResumeTextExtractor resumeTextExtractor;
    private final Path resumeUploadDir;
    private final LuceneVectorIndex vectorIndex;

    public ResumeService(
            ResumeRepository resumeRepository,
            ResumeChunkRepository resumeChunkRepository,
            CurrentUserService currentUserService,
            ResumeTextExtractor resumeTextExtractor,
            @Value("${app.upload.resume-dir:uploads/resumes}") String resumeUploadDir,
            LuceneVectorIndex vectorIndex
    ) {
        this.resumeRepository = resumeRepository;
        this.resumeChunkRepository = resumeChunkRepository;
        this.currentUserService = currentUserService;
        this.resumeTextExtractor = resumeTextExtractor;
        this.resumeUploadDir = Path.of(resumeUploadDir);
        this.vectorIndex = vectorIndex;
    }

    @Transactional(readOnly = true)
    public PageResponse<ResumeResponse> findAll(int page, int size, String keyword) {
        AppUser user = currentUserService.getCurrentUser();
        String safeKeyword = normalizeKeyword(keyword);
        PageRequest pageRequest = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        return PageResponse.from(resumeRepository
                .findByUserIdAndTitleContainingIgnoreCaseOrUserIdAndCandidateNameContainingIgnoreCase(
                        user.getId(),
                        safeKeyword,
                        user.getId(),
                        safeKeyword,
                        pageRequest
                )
                .map(ResumeResponse::from));
    }

    @Transactional(readOnly = true)
    public ResumeResponse findById(Long id) {
        return ResumeResponse.from(getEntityForCurrentUser(id));
    }

    @Transactional
    public ResumeResponse create(ResumeRequest request) {
        Resume resume = new Resume();
        resume.setUser(currentUserService.getCurrentUser());
        applyRequest(resume, request);
        return ResumeResponse.from(resumeRepository.save(resume));
    }

    @Transactional
    public ResumeResponse upload(
            MultipartFile file,
            String title,
            String candidateName,
            String phone,
            String email,
            String rawText
    ) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException("EMPTY_FILE", "resume file must not be empty");
        }

        String originalFileName = file.getOriginalFilename() == null ? "resume" : file.getOriginalFilename();
        String extension = extractExtension(originalFileName);
        if (!ALLOWED_EXTENSIONS.contains(extension)) {
            throw new BusinessException("UNSUPPORTED_FILE_TYPE", "only PDF, DOC, DOCX, TXT and MD files are supported");
        }

        validateUploadMetadata(title, candidateName, email);

        AppUser user = currentUserService.getCurrentUser();
        Path userDir = resumeUploadDir.resolve(user.getId().toString());
        String storedName = UUID.randomUUID() + "." + extension;
        Path storedPath = userDir.resolve(storedName);
        String parsedRawText = limitRawText(resolveRawText(file, rawText));

        try {
            Files.createDirectories(userDir);
            file.transferTo(storedPath);
        } catch (Exception ex) {
            throw new BusinessException("FILE_SAVE_FAILED", "failed to save resume file");
        }

        Resume resume = new Resume();
        resume.setUser(user);
        resume.setTitle(isBlank(title) ? truncate(originalFileName, 120) : title.trim());
        resume.setCandidateName(candidateName);
        resume.setPhone(phone);
        resume.setEmail(email);
        resume.setOriginalFileName(originalFileName);
        resume.setContentType(file.getContentType());
        resume.setFileExtension(extension);
        resume.setStoredFilePath(storedPath.toString());
        resume.setFileSize(file.getSize());
        resume.setRawText(parsedRawText);
        return ResumeResponse.from(resumeRepository.save(resume));
    }

    @Transactional
    public ResumeResponse update(Long id, ResumeRequest request) {
        Resume resume = getEntityForCurrentUser(id);
        applyRequest(resume, request);
        resumeChunkRepository.deleteByResumeId(id);
        return ResumeResponse.from(resumeRepository.save(resume));
    }

    @Transactional
    public void delete(Long id) {
        Resume resume = getEntityForCurrentUser(id);
        Path storedFile = safeStoredFile(resume.getStoredFilePath());
        resumeChunkRepository.deleteByResumeId(id);
        resumeRepository.delete(resume);
        afterCommit(() -> {
            deleteFile(storedFile);
            try {
                vectorIndex.delete(id);
            } catch (Exception ex) {
                log.error("Database row was deleted but vector index cleanup failed for resume {}", id, ex);
            }
        });
    }

    @Transactional(readOnly = true)
    public Resume getEntityForCurrentUser(Long id) {
        AppUser user = currentUserService.getCurrentUser();
        return resumeRepository.findByIdAndUserId(id, user.getId())
                .orElseThrow(() -> new ResourceNotFoundException("resume", id));
    }

    private void applyRequest(Resume resume, ResumeRequest request) {
        resume.setTitle(request.title());
        resume.setCandidateName(request.candidateName());
        resume.setPhone(request.phone());
        resume.setEmail(request.email());
        resume.setRawText(limitRawText(request.rawText()));
    }

    private void validateUploadMetadata(String title, String candidateName, String email) {
        if (isBlank(candidateName)) {
            throw new BusinessException("VALIDATION_ERROR", "candidateName must not be blank");
        }
        if (candidateName.trim().length() > 80) {
            throw new BusinessException("VALIDATION_ERROR", "candidateName must be at most 80 characters");
        }
        if (title != null && title.trim().length() > 120) {
            throw new BusinessException("VALIDATION_ERROR", "title must be at most 120 characters");
        }
        if (!isBlank(email) && !email.contains("@")) {
            throw new BusinessException("VALIDATION_ERROR", "email must be a valid address");
        }
    }

    private String limitRawText(String rawText) {
        if (rawText != null && rawText.length() > ResumeTextExtractor.MAX_RAW_TEXT_CHARS) {
            throw new BusinessException(
                    "RESUME_TEXT_TOO_LONG",
                    "resume text exceeds the maximum of " + ResumeTextExtractor.MAX_RAW_TEXT_CHARS + " characters"
            );
        }
        return rawText;
    }

    private String truncate(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        String trimmed = value.trim();
        return trimmed.length() <= maxLength ? trimmed : trimmed.substring(0, maxLength);
    }

    private String resolveRawText(MultipartFile file, String rawText) {
        if (!isBlank(rawText)) {
            return rawText;
        }
        return resumeTextExtractor.extract(file);
    }

    private String extractExtension(String filename) {
        int dotIndex = filename.lastIndexOf('.');
        if (dotIndex < 0 || dotIndex == filename.length() - 1) {
            throw new BusinessException("MISSING_FILE_EXTENSION", "resume file extension is required");
        }
        return filename.substring(dotIndex + 1).toLowerCase(Locale.ROOT);
    }

    private String normalizeKeyword(String keyword) {
        return keyword == null ? "" : keyword.trim();
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private Path safeStoredFile(String storedFilePath) {
        if (isBlank(storedFilePath)) {
            return null;
        }
        Path uploadRoot = resumeUploadDir.toAbsolutePath().normalize();
        Path storedFile = Path.of(storedFilePath).toAbsolutePath().normalize();
        if (!storedFile.startsWith(uploadRoot)) {
            log.warn("Refusing to delete resume file outside upload root: {}", storedFile);
            return null;
        }
        return storedFile;
    }

    private void deleteFile(Path storedFile) {
        if (storedFile == null) return;
        try {
            Files.deleteIfExists(storedFile);
        } catch (Exception ex) {
            log.error("Database row was deleted but resume file cleanup failed: {}", storedFile, ex);
        }
    }

    private void afterCommit(Runnable cleanup) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    cleanup.run();
                }
            });
        } else {
            cleanup.run();
        }
    }
}
