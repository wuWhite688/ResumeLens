package com.arthur.jdragresume.rag;

import com.arthur.jdragresume.entity.ResumeChunk;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.KnnFloatVectorField;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.Term;
import org.apache.lucene.index.VectorSimilarityFunction;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.KnnFloatVectorQuery;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class LuceneVectorIndex implements AutoCloseable {
    private static final String VECTOR = "embedding";
    private static final String RESUME_ID = "resumeId";
    private static final String SOURCE_HASH = "sourceHash";
    private static final String CHUNK_INDEX = "chunkIndex";

    private final ObjectMapper objectMapper;
    private final Directory directory;
    private final IndexWriter writer;

    public LuceneVectorIndex(
            ObjectMapper objectMapper,
            @Value("${app.rag.lucene-index-dir:data/lucene/resume-vectors}") String indexDir
    ) throws IOException {
        this.objectMapper = objectMapper;
        Path path = Path.of(indexDir).toAbsolutePath().normalize();
        Files.createDirectories(path);
        this.directory = FSDirectory.open(path);
        this.writer = new IndexWriter(directory, new IndexWriterConfig());
    }

    public synchronized boolean isCurrent(Long resumeId, String sourceHash, int expectedChunks) throws IOException {
        writer.commit();
        try (DirectoryReader reader = DirectoryReader.open(writer)) {
            BooleanQuery query = new BooleanQuery.Builder()
                    .add(new TermQuery(new Term(RESUME_ID, resumeId.toString())), BooleanClause.Occur.MUST)
                    .add(new TermQuery(new Term(SOURCE_HASH, sourceHash)), BooleanClause.Occur.MUST)
                    .build();
            return new IndexSearcher(reader).count(query) == expectedChunks;
        }
    }

    public synchronized void replace(Long resumeId, List<ResumeChunk> chunks) throws IOException {
        writer.deleteDocuments(new Term(RESUME_ID, resumeId.toString()));
        for (ResumeChunk chunk : chunks) {
            float[] vector = objectMapper.readValue(chunk.getEmbedding(), float[].class);
            Document document = new Document();
            document.add(new StringField(RESUME_ID, resumeId.toString(), Field.Store.NO));
            document.add(new StringField(SOURCE_HASH, chunk.getSourceHash(), Field.Store.NO));
            document.add(new StoredField(CHUNK_INDEX, chunk.getChunkIndex()));
            document.add(new KnnFloatVectorField(VECTOR, vector, VectorSimilarityFunction.COSINE));
            writer.addDocument(document);
        }
        writer.commit();
    }

    public synchronized void delete(Long resumeId) throws IOException {
        writer.deleteDocuments(new Term(RESUME_ID, resumeId.toString()));
        writer.commit();
    }

    public synchronized List<VectorHit> search(Long resumeId, List<float[]> queries, int limit) throws IOException {
        writer.commit();
        Map<Integer, Float> bestScores = new LinkedHashMap<>();
        try (DirectoryReader reader = DirectoryReader.open(writer)) {
            IndexSearcher searcher = new IndexSearcher(reader);
            TermQuery filter = new TermQuery(new Term(RESUME_ID, resumeId.toString()));
            for (float[] query : queries) {
                ScoreDoc[] hits = searcher.search(new KnnFloatVectorQuery(VECTOR, query, limit, filter), limit).scoreDocs;
                for (ScoreDoc hit : hits) {
                    int chunkIndex = searcher.storedFields().document(hit.doc).getField(CHUNK_INDEX).numericValue().intValue();
                    bestScores.merge(chunkIndex, hit.score, Math::max);
                }
            }
        }
        List<VectorHit> result = new ArrayList<>();
        bestScores.forEach((index, score) -> result.add(new VectorHit(index, score)));
        result.sort((left, right) -> Float.compare(right.score(), left.score()));
        return result.stream().limit(limit).toList();
    }

    @PreDestroy
    @Override
    public synchronized void close() throws IOException {
        writer.close();
        directory.close();
    }

    public record VectorHit(int chunkIndex, float score) {}
}
