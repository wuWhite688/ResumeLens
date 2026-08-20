package com.arthur.jdragresume.dto.job;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

public record JobDescriptionBulkImportRequest(
        @NotEmpty @Size(max = 50) List<@Valid JobDescriptionRequest> items
) {
}
