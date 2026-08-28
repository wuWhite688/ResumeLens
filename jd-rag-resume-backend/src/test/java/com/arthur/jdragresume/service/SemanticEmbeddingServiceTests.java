package com.arthur.jdragresume.service;

import com.arthur.jdragresume.entity.JobDescription;
import com.arthur.jdragresume.entity.Resume;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SemanticEmbeddingServiceTests {
    @Test
    void storesModelBoundVectorsAndInvalidatesChangedContent() {
        SemanticEmbeddingService service = SemanticEmbeddingTestSupport.service(
                text -> text.contains("Java")
                        ? new float[]{1.0f, 0.0f, 0.0f}
                        : new float[]{0.0f, 1.0f, 0.0f},
                3
        );

        Resume resume = resume("Java Spring Boot");
        JobDescription javaJob = job("Java 后端", "Java Spring Boot");
        JobDescription designJob = job("产品设计", "Figma UX");

        service.refresh(resume);
        service.refreshJobs(List.of(javaJob, designJob));

        assertTrue(service.isCurrent(resume));
        assertTrue(service.isCurrent(javaJob));
        assertEquals(1.0, service.similarity(resume, javaJob), 0.000001);
        assertEquals(0.0, service.similarity(resume, designJob), 0.000001);
        assertEquals(service.modelKey(), resume.getSemanticEmbeddingModelKey());

        javaJob.setDescription("Go 后端");
        assertFalse(service.isCurrent(javaJob));

        resume.setSemanticEmbedding(SemanticEmbeddingService.encode(new float[]{Float.NaN, 0.0f, 0.0f}));
        assertFalse(service.isCurrent(resume));
    }

    @Test
    void floatVectorBinaryEncodingRoundTripsWithoutJsonExpansion() {
        float[] vector = {0.25f, -0.5f, 1.0f};
        byte[] bytes = SemanticEmbeddingService.encode(vector);

        assertEquals(vector.length * Float.BYTES, bytes.length);
        assertArrayEquals(vector, SemanticEmbeddingService.decode(bytes));
    }

    private static Resume resume(String rawText) {
        Resume resume = new Resume();
        resume.setTitle("Arthur Resume");
        resume.setRawText(rawText);
        return resume;
    }

    private static JobDescription job(String title, String description) {
        JobDescription job = new JobDescription();
        job.setTitle(title);
        job.setCompanyName("Example");
        job.setDescription(description);
        job.setRequirements("");
        return job;
    }
}
