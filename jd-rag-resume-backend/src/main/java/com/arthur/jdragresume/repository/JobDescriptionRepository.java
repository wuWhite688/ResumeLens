package com.arthur.jdragresume.repository;

import com.arthur.jdragresume.entity.JobDescription;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface JobDescriptionRepository extends JpaRepository<JobDescription, Long> {
    Optional<JobDescription> findByIdAndUserId(Long id, Long userId);

    Optional<JobDescription> findByUserIdAndSourcePlatformAndSourceJobId(
            Long userId,
            String sourcePlatform,
            String sourceJobId
    );

    long countByUserId(Long userId);

    List<JobDescription> findAllByUserId(Long userId);

    Page<JobDescription> findByUserIdAndTitleContainingIgnoreCaseOrUserIdAndCompanyNameContainingIgnoreCase(
            Long userIdForTitle,
            String titleKeyword,
            Long userIdForCompanyName,
            String companyNameKeyword,
            Pageable pageable
    );
}
