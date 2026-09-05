package com.arthur.jdragresume.repository;

import com.arthur.jdragresume.entity.AnalysisSubmissionLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;

public interface AnalysisSubmissionLogRepository extends JpaRepository<AnalysisSubmissionLog, Long> {
    long countByUser_IdAndCreatedAtAfter(Long userId, LocalDateTime createdAt);
}
