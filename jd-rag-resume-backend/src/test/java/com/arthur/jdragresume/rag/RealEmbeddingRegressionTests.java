package com.arthur.jdragresume.rag;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledIfEnvironmentVariable(named = "RUN_EMBEDDING_REGRESSION", matches = "true")
class RealEmbeddingRegressionTests {
    private static final String MODEL_REVISION = "2edbf5e672aab465f9ed4c154a8b61791c082c69";
    private static final String TOKENIZER_SHA256 = "3a56def25aa40facc030ea8b0b87f3688e4b3c39eb8b45d5702b3a1300fe2a20";
    private static final String MODEL_SHA256 = "ab2bd164ebd8ca9003dc49a981b611e849b5d326f504c8873ba76e07fa6c0082";
    private static final String REMOTE_TOKENIZER =
            "https://huggingface.co/onnx-community/gte-multilingual-base/resolve/" + MODEL_REVISION + "/tokenizer.json";
    private static final String REMOTE_MODEL =
            "https://huggingface.co/onnx-community/gte-multilingual-base/resolve/" + MODEL_REVISION + "/onnx/model_int8.onnx";

    @Test
    void relevantBackendEvidenceBeatsUnrelatedArtEvidenceByUsefulMargin() throws Exception {
        ClsOnnxEmbeddingModel model = new ClsOnnxEmbeddingModel(
                resolveResource(
                        "RAG_EMBEDDING_TOKENIZER_URI",
                        Path.of("models", "gte-multilingual-base-int8", "tokenizer.json"),
                        REMOTE_TOKENIZER
                ),
                resolveResource(
                        "RAG_EMBEDDING_MODEL_URI",
                        Path.of("models", "gte-multilingual-base-int8", "model_int8.onnx"),
                        REMOTE_MODEL
                ),
                TOKENIZER_SHA256,
                MODEL_SHA256,
                "token_embeddings",
                Map.of("padding", "true", "truncation", "true", "modelMaxLength", "8192", "maxLength", "8192"),
                768
        );
        try {
            model.afterPropertiesSet();
            String query = "Represent this sentence for searching relevant passages:\nJava Spring Boot backend engineer, MySQL, Redis and Docker";
            List<float[]> vectors = model.embed(List.of(
                    query,
                    "Java backend developer using Spring Boot, MySQL, Redis and Docker to build REST APIs.",
                    "Watercolor artist and museum curator focused on landscape exhibitions and oil painting."
            ));
            double relevant = ResumeRagService.cosineSimilarity(vectors.get(0), vectors.get(1));
            double unrelated = ResumeRagService.cosineSimilarity(vectors.get(0), vectors.get(2));
            System.out.printf("real-embedding-regression relevant=%.4f unrelated=%.4f margin=%.4f%n",
                    relevant, unrelated, relevant - unrelated);

            assertTrue(relevant >= 0.70, () -> "relevant similarity too low: " + relevant);
            assertTrue(relevant - unrelated >= 0.20,
                    () -> "semantic margin too small: relevant=" + relevant + ", unrelated=" + unrelated);
        } finally {
            model.destroy();
        }
    }

    private static String resolveResource(String environmentVariable, Path localPath, String remoteUri) {
        String configured = System.getenv(environmentVariable);
        if (configured != null && !configured.isBlank()) {
            return configured;
        }
        Path absoluteLocalPath = localPath.toAbsolutePath().normalize();
        return Files.isRegularFile(absoluteLocalPath) ? absoluteLocalPath.toUri().toString() : remoteUri;
    }
}
