package com.arthur.jdragresume.repository;

import com.arthur.jdragresume.entity.AnalysisHistory;
import com.arthur.jdragresume.entity.AnalysisStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.time.LocalDateTime;

public interface AnalysisHistoryRepository extends JpaRepository<AnalysisHistory, Long> {
    Optional<AnalysisHistory> findByIdAndUserId(Long id, Long userId);

    boolean existsByUser_IdAndResume_IdAndJobDescription_IdAndStatus(
            Long userId,
            Long resumeId,
            Long jobDescriptionId,
            AnalysisStatus status
    );

    long countByUser_IdAndStatus(Long userId, AnalysisStatus status);

    long countByUser_IdAndCreatedAtAfter(Long userId, LocalDateTime createdAt);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select history from AnalysisHistory history where history.id = :id")
    Optional<AnalysisHistory> findByIdForUpdate(@Param("id") Long id);

    @Query("""
            select history from AnalysisHistory history
            join fetch history.user
            join fetch history.resume
            join fetch history.jobDescription
            where history.id = :id
            """)
    Optional<AnalysisHistory> findWithDetailsById(@Param("id") Long id);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update AnalysisHistory history
            set history.status = com.arthur.jdragresume.entity.AnalysisStatus.FAILED,
                history.summary = :summary
            where history.status = com.arthur.jdragresume.entity.AnalysisStatus.PENDING
              and history.updatedAt < :cutoff
            """)
    int failStalePending(@Param("cutoff") LocalDateTime cutoff, @Param("summary") String summary);

    @Query("""
            select history from AnalysisHistory history
            where history.user.id = :userId
              and (
                lower(history.resume.title) like lower(concat('%', :keyword, '%'))
                or lower(history.jobDescription.title) like lower(concat('%', :keyword, '%'))
              )
            """)
    Page<AnalysisHistory> searchByUserId(
            @Param("userId") Long userId,
            @Param("keyword") String keyword,
            Pageable pageable
    );
}
