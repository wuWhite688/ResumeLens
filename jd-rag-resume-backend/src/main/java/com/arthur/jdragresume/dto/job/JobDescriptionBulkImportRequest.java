package com.arthur.jdragresume.dto.job;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

public record JobDescriptionBulkImportRequest(
        @NotEmpty List<@Valid JobDescriptionRequest> items
) {
}
