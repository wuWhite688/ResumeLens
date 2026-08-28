package com.arthur.jdragresume.service;

import com.arthur.jdragresume.rag.RagProperties;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

public final class SemanticEmbeddingTestSupport {
    private SemanticEmbeddingTestSupport() {
    }

    public static SemanticEmbeddingService service() {
        return service(text -> new float[]{1.0f, 0.0f, 0.0f}, 3);
    }

    public static SemanticEmbeddingService service(Function<String, float[]> vectorFactory, int dimensions) {
        RagProperties properties = properties(dimensions);
        return new SemanticEmbeddingService(model(vectorFactory), properties);
    }

    public static RagProperties properties(int dimensions) {
        RagProperties properties = new RagProperties();
        properties.setEmbeddingDimensions(dimensions);
        properties.setEmbeddingBatchSize(2);
        return properties;
    }

    public static EmbeddingModel model(Function<String, float[]> vectorFactory) {
        return new EmbeddingModel() {
            @Override
            public float[] embed(String text) {
                return vectorFactory.apply(text);
            }

            @Override
            public List<float[]> embed(List<String> texts) {
                return texts.stream().map(vectorFactory).toList();
            }

            @Override
            public float[] embed(Document document) {
                return embed(document.getText());
            }

            @Override
            public EmbeddingResponse call(EmbeddingRequest request) {
                List<Embedding> results = new ArrayList<>();
                List<String> texts = request.getInstructions();
                for (int index = 0; index < texts.size(); index++) {
                    results.add(new Embedding(vectorFactory.apply(texts.get(index)), index));
                }
                return new EmbeddingResponse(results);
            }
        };
    }
}
