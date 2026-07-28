package com.arthur.jdragresume.rag;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TextChunkerTests {
    @Test
    void splitsLongTextWithinConfiguredLimit() {
        RagProperties properties = new RagProperties();
        properties.setChunkSize(220);
        properties.setChunkOverlap(30);
        TextChunker chunker = new TextChunker(properties);
        String text = ("Java Spring Boot MySQL JWT REST API testing deployment performance tuning. ").repeat(12);

        List<String> chunks = chunker.split(text);

        assertTrue(chunks.size() > 1);
        assertTrue(chunks.stream().allMatch(chunk -> chunk.length() <= 220));
        assertTrue(chunks.stream().allMatch(chunk -> !chunk.isBlank()));
    }

    @Test
    void returnsNoChunksForBlankText() {
        TextChunker chunker = new TextChunker(new RagProperties());

        assertEquals(List.of(), chunker.split("  \n  "));
    }

    @Test
    void preservesShortTextAsOneChunk() {
        TextChunker chunker = new TextChunker(new RagProperties());

        List<String> chunks = chunker.split("Java backend engineer");

        assertEquals(1, chunks.size());
        assertFalse(chunks.getFirst().isBlank());
    }

    @Test
    void defaultChunkSizeIsNotForcedByFalse512Limit() {
        RagProperties properties = new RagProperties();
        // gte-multilingual-base supports 8192 tokens; ~900 chars is intentional.
        assertEquals(900, properties.getChunkSize());
        assertEquals(8192, properties.getMaxLength());
        assertEquals("cls", properties.getPoolingMode());
        assertTrue(properties.getMinSimilarity() > 0);
    }
}

