package com.arthur.jdragresume.dto.analysis;

import com.arthur.jdragresume.entity.AnalysisHistory;
import com.arthur.jdragresume.entity.AnalysisStatus;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record AnalysisHistoryResponse(
        Long id,
        Long userId,
        String username,
        Long resumeId,
        String resumeTitle,
        Long jobDescriptionId,
        String jobTitle,
        BigDecimal matchScore,
        AnalysisStatus status,
        String summary,
        String retrievedContext,
        String strengths,
        String missingSkills,
        String improvementSuggestions,
        String interviewQuestions,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
    public static AnalysisHistoryResponse from(AnalysisHistory history) {
        return new AnalysisHistoryResponse(
                history.getId(),
                history.getUser().getId(),
                history.getUser().getUsername(),
                history.getResume().getId(),
                history.getResume().getTitle(),
                history.getJobDescription().getId(),
                history.getJobDescription().getTitle(),
                history.getMatchScore(),
                history.getStatus(),
                history.getSummary(),
                history.getRetrievedContext(),
                history.getStrengths(),
                history.getMissingSkills(),
                history.getImprovementSuggestions(),
                history.getInterviewQuestions(),
                history.getCreatedAt(),
                history.getUpdatedAt()
        );
    }
}
