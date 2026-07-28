package com.arthur.jdragresume.rag;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "app.rag")
public class RagProperties {
    /**
     * Character window for resume chunking. gte-multilingual-base supports 8192 tokens,
     * so ~900 CJK/Latin characters is not truncated by a 512-token limit.
     */
    private int chunkSize = 900;
    private int chunkOverlap = 120;
    private int topK = 5;
    /** Drop chunks below this cosine similarity before Top-K. */
    private double minSimilarity = 0.55;
    /** Add a small boost when JD keywords appear in a resume chunk. */
    private boolean hybridEnabled = true;
    private double keywordBoost = 0.035;
    private int maxKeywordBoosts = 3;
    /** Embed JD full text and requirements separately, then take max similarity. */
    private boolean dualQueryEnabled = true;
    private int maxLength = 8192;
    private int embeddingDimensions = 768;
    /**
     * ONNX output tensor used for CLS extraction.
     * onnx-community export names this {@code token_embeddings} (equivalent to last_hidden_state).
     */
    private String modelOutputName = "token_embeddings";
    private String poolingMode = "cls";
    private String embeddingModelId = "Alibaba-NLP/gte-multilingual-base-int8";
    private String embeddingTokenizerUri = "https://huggingface.co/onnx-community/gte-multilingual-base/resolve/main/tokenizer.json";
    private String embeddingModelUri = "https://huggingface.co/onnx-community/gte-multilingual-base/resolve/main/onnx/model_int8.onnx";
    private String queryPrefix = "Represent this sentence for searching relevant passages:";

    public int getChunkSize() {
        return chunkSize;
    }

    public void setChunkSize(int chunkSize) {
        this.chunkSize = chunkSize;
    }

    public int getChunkOverlap() {
        return chunkOverlap;
    }

    public void setChunkOverlap(int chunkOverlap) {
        this.chunkOverlap = chunkOverlap;
    }

    public int getTopK() {
        return topK;
    }

    public void setTopK(int topK) {
        this.topK = topK;
    }

    public double getMinSimilarity() {
        return minSimilarity;
    }

    public void setMinSimilarity(double minSimilarity) {
        this.minSimilarity = minSimilarity;
    }

    public boolean isHybridEnabled() {
        return hybridEnabled;
    }

    public void setHybridEnabled(boolean hybridEnabled) {
        this.hybridEnabled = hybridEnabled;
    }

    public double getKeywordBoost() {
        return keywordBoost;
    }

    public void setKeywordBoost(double keywordBoost) {
        this.keywordBoost = keywordBoost;
    }

    public int getMaxKeywordBoosts() {
        return maxKeywordBoosts;
    }

    public void setMaxKeywordBoosts(int maxKeywordBoosts) {
        this.maxKeywordBoosts = maxKeywordBoosts;
    }

    public boolean isDualQueryEnabled() {
        return dualQueryEnabled;
    }

    public void setDualQueryEnabled(boolean dualQueryEnabled) {
        this.dualQueryEnabled = dualQueryEnabled;
    }

    public int getMaxLength() {
        return maxLength;
    }

    public void setMaxLength(int maxLength) {
        this.maxLength = maxLength;
    }

    public int getEmbeddingDimensions() {
        return embeddingDimensions;
    }

    public void setEmbeddingDimensions(int embeddingDimensions) {
        this.embeddingDimensions = embeddingDimensions;
    }

    public String getModelOutputName() {
        return modelOutputName;
    }

    public void setModelOutputName(String modelOutputName) {
        this.modelOutputName = modelOutputName;
    }

    public String getPoolingMode() {
        return poolingMode;
    }

    public void setPoolingMode(String poolingMode) {
        this.poolingMode = poolingMode;
    }

    public String getEmbeddingModelId() {
        return embeddingModelId;
    }

    public void setEmbeddingModelId(String embeddingModelId) {
        this.embeddingModelId = embeddingModelId;
    }

    public String getEmbeddingTokenizerUri() {
        return embeddingTokenizerUri;
    }

    public void setEmbeddingTokenizerUri(String embeddingTokenizerUri) {
        this.embeddingTokenizerUri = embeddingTokenizerUri;
    }

    public String getEmbeddingModelUri() {
        return embeddingModelUri;
    }

    public void setEmbeddingModelUri(String embeddingModelUri) {
        this.embeddingModelUri = embeddingModelUri;
    }

    public String getQueryPrefix() {
        return queryPrefix;
    }

    public void setQueryPrefix(String queryPrefix) {
        this.queryPrefix = queryPrefix;
    }
}
