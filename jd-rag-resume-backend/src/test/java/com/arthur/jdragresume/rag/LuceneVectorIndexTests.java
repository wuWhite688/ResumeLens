package com.arthur.jdragresume.rag;

import com.arthur.jdragresume.entity.ResumeChunk;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LuceneVectorIndexTests {
    @TempDir Path tempDir;

    @Test
    void indexesFiltersAndRanksNearestResumeChunk() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        try (LuceneVectorIndex index = new LuceneVectorIndex(mapper, tempDir.toString())) {
            index.replace(10L, List.of(
                    chunk(0, "hash-a", new float[]{1, 0, 0}, mapper),
                    chunk(1, "hash-a", new float[]{0, 1, 0}, mapper)
            ));
            index.replace(20L, List.of(chunk(0, "hash-b", new float[]{1, 0, 0}, mapper)));

            assertTrue(index.isCurrent(10L, "hash-a", 2));
            List<LuceneVectorIndex.VectorHit> hits = index.search(10L, List.of(new float[]{0.9f, 0.1f, 0}), 2);

            assertEquals(List.of(0, 1), hits.stream().map(LuceneVectorIndex.VectorHit::chunkIndex).toList());
        }
    }

    private ResumeChunk chunk(int index, String hash, float[] vector, ObjectMapper mapper) throws Exception {
        ResumeChunk chunk = new ResumeChunk();
        chunk.setChunkIndex(index);
        chunk.setSourceHash(hash);
        chunk.setContent("chunk-" + index);
        chunk.setEmbedding(mapper.writeValueAsString(vector));
        return chunk;
    }
}
