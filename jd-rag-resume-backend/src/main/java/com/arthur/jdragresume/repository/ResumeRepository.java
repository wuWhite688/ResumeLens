package com.arthur.jdragresume.repository;

import com.arthur.jdragresume.entity.Resume;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ResumeRepository extends JpaRepository<Resume, Long> {
    Optional<Resume> findByIdAndUserId(Long id, Long userId);

    Page<Resume> findByUserIdAndTitleContainingIgnoreCaseOrUserIdAndCandidateNameContainingIgnoreCase(
            Long userIdForTitle,
            String titleKeyword,
            Long userIdForCandidateName,
            String candidateNameKeyword,
            Pageable pageable
    );
}
