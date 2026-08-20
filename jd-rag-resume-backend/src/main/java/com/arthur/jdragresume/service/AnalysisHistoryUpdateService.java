package com.arthur.jdragresume.service;

import com.arthur.jdragresume.entity.AnalysisHistory;
import com.arthur.jdragresume.entity.AnalysisStatus;
import com.arthur.jdragresume.repository.AnalysisHistoryRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.function.Consumer;

@Service
public class AnalysisHistoryUpdateService {
    private final AnalysisHistoryRepository historyRepository;

    public AnalysisHistoryUpdateService(AnalysisHistoryRepository historyRepository) {
        this.historyRepository = historyRepository;
    }

    @Transactional
    public boolean completeIfPending(Long id, Consumer<AnalysisHistory> mutator) {
        AnalysisHistory history = historyRepository.findByIdForUpdate(id).orElse(null);
        if (history == null || history.getStatus() != AnalysisStatus.PENDING) {
            return false;
        }
        mutator.accept(history);
        history.setStatus(AnalysisStatus.COMPLETED);
        return true;
    }

    @Transactional
    public boolean failIfPending(Long id, String summary) {
        AnalysisHistory history = historyRepository.findByIdForUpdate(id).orElse(null);
        if (history == null || history.getStatus() != AnalysisStatus.PENDING) {
            return false;
        }
        history.setStatus(AnalysisStatus.FAILED);
        history.setSummary(summary);
        return true;
    }
}
