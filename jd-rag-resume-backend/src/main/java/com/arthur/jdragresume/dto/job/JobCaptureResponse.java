package com.arthur.jdragresume.dto.job;

public record JobCaptureResponse(
        JobDescriptionResponse job,
        boolean existingJob,
        boolean contentChanged
) {
}
