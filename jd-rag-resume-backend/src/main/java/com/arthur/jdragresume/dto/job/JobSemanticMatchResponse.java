package com.arthur.jdragresume.dto.job;

import com.arthur.jdragresume.entity.JobDescription;

/**
 * Cheap first-stage retrieval result. Similarity is deliberately kept separate
 * from the final LLM/RAG match score exposed by AnalysisHistoryResponse.
 */
public record JobSemanticMatchResponse(
        JobDescriptionResponse job,
        double similarity
) {
    public static JobSemanticMatchResponse from(JobDescription job, double similarity) {
        return new JobSemanticMatchResponse(JobDescriptionResponse.from(job), similarity);
    }
}
