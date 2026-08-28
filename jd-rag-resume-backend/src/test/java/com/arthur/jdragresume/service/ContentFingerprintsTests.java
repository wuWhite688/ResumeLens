package com.arthur.jdragresume.service;

import com.arthur.jdragresume.entity.JobDescription;
import com.arthur.jdragresume.entity.Resume;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class ContentFingerprintsTests {

    @Test
    void insignificantJobWhitespaceAndCaseDoNotChangeTheFingerprint() {
        String first = ContentFingerprints.job(
                "Java Engineer", "Example Inc", "Hangzhou", "Full Time", "Build APIs", "Java  Spring"
        );
        String second = ContentFingerprints.job(
                " java engineer ", "EXAMPLE INC", "hangzhou", "full time", "build apis", "Java\nSpring"
        );

        assertEquals(first, second);
    }

    @Test
    void editedResumeTextChangesTheFingerprint() {
        Resume resume = new Resume();
        resume.setTitle("Backend resume");
        resume.setRawText("Java Spring Boot");
        String before = ContentFingerprints.resume(resume);

        resume.setRawText("Java Spring Boot Redis");

        assertNotEquals(before, ContentFingerprints.resume(resume));
    }

    @Test
    void editedJobDescriptionChangesTheFingerprint() {
        JobDescription job = new JobDescription();
        job.setTitle("Java engineer");
        job.setDescription("Build APIs");
        String before = ContentFingerprints.job(job);

        job.setDescription("Build distributed APIs");

        assertNotEquals(before, ContentFingerprints.job(job));
    }
}
