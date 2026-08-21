package com.arthur.jdragresume.dto.resume;

import com.arthur.jdragresume.entity.Resume;

import java.time.LocalDateTime;

public record ResumeResponse(
        Long id,
        Long userId,
        String username,
        String title,
        String candidateName,
        String phone,
        String email,
        String originalFileName,
        String contentType,
        String fileExtension,
        Long fileSize,
        String rawText,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
    public static ResumeResponse from(Resume resume) {
        return from(resume, true);
    }

    public static ResumeResponse summary(Resume resume) {
        return from(resume, false);
    }

    private static ResumeResponse from(Resume resume, boolean includeRawText) {
        return new ResumeResponse(
                resume.getId(),
                resume.getUser().getId(),
                resume.getUser().getUsername(),
                resume.getTitle(),
                resume.getCandidateName(),
                resume.getPhone(),
                resume.getEmail(),
                resume.getOriginalFileName(),
                resume.getContentType(),
                resume.getFileExtension(),
                resume.getFileSize(),
                includeRawText ? resume.getRawText() : null,
                resume.getCreatedAt(),
                resume.getUpdatedAt()
        );
    }
}
