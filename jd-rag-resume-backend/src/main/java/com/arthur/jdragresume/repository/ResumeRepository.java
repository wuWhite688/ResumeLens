package com.arthur.jdragresume.repository;

import com.arthur.jdragresume.entity.Resume;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface ResumeRepository extends JpaRepository<Resume, Long> {
    Optional<Resume> findByIdAndUserId(Long id, Long userId);

    long countByUserId(Long userId);

    @Query("select coalesce(sum(resume.fileSize), 0) from Resume resume where resume.user.id = :userId")
    long sumFileSizeByUserId(@Param("userId") Long userId);

    Page<Resume> findByUserIdAndTitleContainingIgnoreCaseOrUserIdAndCandidateNameContainingIgnoreCase(
            Long userIdForTitle,
            String titleKeyword,
            Long userIdForCandidateName,
            String candidateNameKeyword,
            Pageable pageable
    );
}
