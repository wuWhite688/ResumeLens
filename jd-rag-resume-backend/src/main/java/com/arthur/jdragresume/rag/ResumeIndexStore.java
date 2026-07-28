package com.arthur.jdragresume.rag;

import com.arthur.jdragresume.entity.ResumeChunk;
import com.arthur.jdragresume.repository.ResumeChunkRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class ResumeIndexStore {
    private final ResumeChunkRepository repository;

    public ResumeIndexStore(ResumeChunkRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public List<ResumeChunk> replace(Long resumeId, List<ResumeChunk> replacements) {
        repository.deleteByResumeId(resumeId);
        repository.flush();
        return repository.saveAllAndFlush(replacements);
    }
}
