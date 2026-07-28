package com.arthur.jdragresume.service;

import com.arthur.jdragresume.exception.BusinessException;
import org.springframework.stereotype.Component;

@Component
public class ResumeTextQualityValidator {
    private static final int MIN_TEXT_LENGTH = 40;

    public String validate(String text) {
        String normalized = text == null ? "" : text.replace('\u0000', ' ').trim();
        if (normalized.length() < MIN_TEXT_LENGTH) {
            throw lowQuality("parsed resume text is too short");
        }
        long meaningful = normalized.codePoints().filter(Character::isLetterOrDigit).count();
        long replacement = normalized.codePoints().filter(codePoint -> codePoint == 0xfffd).count();
        if ((double) meaningful / normalized.codePointCount(0, normalized.length()) < 0.15) {
            throw lowQuality("parsed resume text contains too little readable content");
        }
        if (replacement > Math.max(2, normalized.length() / 100)) {
            throw lowQuality("parsed resume text appears to have encoding corruption");
        }
        return normalized;
    }

    private BusinessException lowQuality(String message) {
        return new BusinessException("RESUME_PARSE_LOW_QUALITY", message);
    }
}
