package com.arthur.jdragresume.rag;

import java.util.List;

public record RetrievedChunk(
        int chunkIndex,
        String content,
        double similarity,
        double rawSimilarity,
        boolean kept,
        String status,
        String section,
        List<String> boostKeywords
) {
    public RetrievedChunk {
        if (boostKeywords == null) {
            boostKeywords = List.of();
        } else {
            boostKeywords = List.copyOf(boostKeywords);
        }
        if (section == null || section.isBlank()) {
            section = "正文";
        }
        if (status == null || status.isBlank()) {
            status = kept ? "kept" : "filtered";
        }
    }
}
