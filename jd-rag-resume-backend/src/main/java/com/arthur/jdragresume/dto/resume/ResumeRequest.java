package com.arthur.jdragresume.dto.resume;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ResumeRequest(
        @NotBlank @Size(max = 120) String title,
        @NotBlank @Size(max = 80) String candidateName,
        @Size(max = 40) String phone,
        @Email @Size(max = 128) String email,
        @NotBlank String rawText
) {
}
