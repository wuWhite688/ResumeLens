package com.arthur.jdragresume.service;

import com.arthur.jdragresume.exception.BusinessException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ResumeTextQualityValidatorTests {
    private final ResumeTextQualityValidator validator = new ResumeTextQualityValidator();

    @Test
    void acceptsReadableResumeText() {
        String text = "Arthur Chen Java backend engineer with Spring Boot, MySQL, Redis and Docker project experience.";
        assertEquals(text, validator.validate(text));
    }

    @Test
    void rejectsEmptyOrVeryShortExtraction() {
        BusinessException exception = assertThrows(BusinessException.class, () -> validator.validate("Java developer"));
        assertEquals("RESUME_PARSE_LOW_QUALITY", exception.getCode());
    }

    @Test
    void rejectsUnreadableExtraction() {
        BusinessException exception = assertThrows(BusinessException.class,
                () -> validator.validate("�".repeat(80) + "---___..."));
        assertEquals("RESUME_PARSE_LOW_QUALITY", exception.getCode());
    }
}
