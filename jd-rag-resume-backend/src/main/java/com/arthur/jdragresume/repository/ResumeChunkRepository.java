package com.arthur.jdragresume.repository;

import com.arthur.jdragresume.entity.ResumeChunk;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

public interface ResumeChunkRepository extends JpaRepository<ResumeChunk, Long> {
    List<ResumeChunk> findByResumeIdOrderByChunkIndexAsc(Long resumeId);

    @Transactional
    long deleteByResumeId(Long resumeId);
}
