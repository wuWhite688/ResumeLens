package com.arthur.jdragresume.service;

import com.arthur.jdragresume.entity.JobDescription;
import com.arthur.jdragresume.entity.Resume;
import com.arthur.jdragresume.rag.ClsOnnxEmbeddingModel;
import com.arthur.jdragresume.rag.RagProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledIfEnvironmentVariable(named = "RUN_EMBEDDING_REGRESSION", matches = "true")
class SemanticRankingRealEmbeddingTests {
    @Test
    void javaResumeRanksJavaJobAboveUnrelatedArtJob() throws Exception {
        Path modelDir = Path.of("models", "gte-multilingual-base-int8").toAbsolutePath().normalize();
        RagProperties properties = new RagProperties();
        properties.setEmbeddingTokenizerUri(modelDir.resolve("tokenizer.json").toUri().toString());
        properties.setEmbeddingModelUri(modelDir.resolve("model_int8.onnx").toUri().toString());
        ClsOnnxEmbeddingModel model = new ClsOnnxEmbeddingModel(
                properties.getEmbeddingTokenizerUri(),
                properties.getEmbeddingModelUri(),
                properties.getEmbeddingTokenizerSha256(),
                properties.getEmbeddingModelSha256(),
                properties.getModelOutputName(),
                Map.of(
                        "padding", "true",
                        "truncation", "true",
                        "modelMaxLength", String.valueOf(properties.getMaxLength()),
                        "maxLength", String.valueOf(properties.getMaxLength())
                ),
                properties.getEmbeddingDimensions()
        );
        try {
            model.afterPropertiesSet();
            SemanticEmbeddingService service = new SemanticEmbeddingService(model, properties);
            Resume resume = new Resume();
            resume.setTitle("Java 后端简历");
            resume.setRawText("使用 Java、Spring Boot、MySQL、Redis 和 Docker 开发 REST API，并参与 RAG 检索服务建设。");
            JobDescription relevant = job(
                    "Java RAG 后端工程师",
                    "负责 Spring Boot 服务与向量检索链路",
                    "Java、MySQL、Redis、Docker、Embedding"
            );
            JobDescription unrelated = job(
                    "美术策展人",
                    "负责油画展览策划和艺术家合作",
                    "艺术史、博物馆策展、油画"
            );

            service.refresh(resume);
            service.refreshJobs(List.of(relevant, unrelated));
            double relevantScore = service.similarity(resume, relevant);
            double unrelatedScore = service.similarity(resume, unrelated);
            System.out.printf(
                    "semantic-ranking-real relevant=%.4f unrelated=%.4f margin=%.4f%n",
                    relevantScore,
                    unrelatedScore,
                    relevantScore - unrelatedScore
            );

            assertTrue(relevantScore > unrelatedScore + 0.15,
                    () -> "expected a useful semantic margin, got " + relevantScore + " vs " + unrelatedScore);
        } finally {
            model.destroy();
        }
    }

    private static JobDescription job(String title, String description, String requirements) {
        JobDescription job = new JobDescription();
        job.setTitle(title);
        job.setCompanyName("Example");
        job.setDescription(description);
        job.setRequirements(requirements);
        return job;
    }
}
