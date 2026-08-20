package com.arthur.jdragresume.dto.job;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record JobDescriptionRequest(
        @NotBlank @Size(max = 160) String title,
        @NotBlank @Size(max = 120) String companyName,
        @Size(max = 80) String location,
        @Size(max = 60) String employmentType,
        @NotBlank @Size(max = 20000) String description,
        @Size(max = 20000) String requirements
) {
}
