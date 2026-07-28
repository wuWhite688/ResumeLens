package com.arthur.jdragresume.rag;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClsOnnxEmbeddingModelTests {
    @Test
    void extractsFirstTokenAsClsAndL2Normalizes() {
        float[][][] tokenEmbeddings = new float[][][]{
                {
                        {3.0f, 0.0f, 0.0f}, // CLS
                        {9.0f, 9.0f, 9.0f}, // should be ignored by CLS pooling
                        {1.0f, 2.0f, 3.0f}
                }
        };

        List<float[]> embeddings = ClsOnnxEmbeddingModel.extractClsEmbeddings(tokenEmbeddings);

        assertEquals(1, embeddings.size());
        float[] vector = embeddings.getFirst();
        assertEquals(3, vector.length);
        assertEquals(1.0f, vector[0], 1e-5);
        assertEquals(0.0f, vector[1], 1e-5);
        assertEquals(0.0f, vector[2], 1e-5);
    }

    @Test
    void normalizesAlreadyPooledBatchMatrix() {
        float[][] matrix = new float[][]{
                {0.0f, 4.0f}
        };
        List<float[]> embeddings = ClsOnnxEmbeddingModel.extractClsEmbeddings(matrix);
        assertArrayEquals(new float[]{0.0f, 1.0f}, embeddings.getFirst(), 1e-5f);
    }

    @Test
    void meanPoolingWouldDifferFromCls() {
        // Documents that mean != CLS; regression guard for "do not mean-pool GTE".
        float[] cls = {1.0f, 0.0f};
        float[] other = {0.0f, 1.0f};
        float[] mean = {
                (cls[0] + other[0]) / 2.0f,
                (cls[1] + other[1]) / 2.0f
        };
        float[][][] tokens = new float[][][]{{cls, other}};
        float[] actualCls = ClsOnnxEmbeddingModel.extractClsEmbeddings(tokens).getFirst();

        assertTrue(Math.abs(actualCls[0] - mean[0]) > 0.1);
        assertEquals(1.0f, actualCls[0], 1e-5);
        assertEquals(0.0f, actualCls[1], 1e-5);
    }
}
