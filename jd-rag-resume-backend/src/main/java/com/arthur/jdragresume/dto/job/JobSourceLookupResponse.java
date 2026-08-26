package com.arthur.jdragresume.dto.job;

public record JobSourceLookupResponse(
        boolean found,
        JobDescriptionResponse job
) {
    public static JobSourceLookupResponse missing() {
        return new JobSourceLookupResponse(false, null);
    }

    public static JobSourceLookupResponse found(JobDescriptionResponse job) {
        return new JobSourceLookupResponse(true, job);
    }
}
