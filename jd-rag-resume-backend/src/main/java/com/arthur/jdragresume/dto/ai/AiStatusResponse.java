package com.arthur.jdragresume.dto.ai;

/**
 * 公开只读的运行状态。刻意只暴露界面需要展示的配置，
 * 不包含 api-key、base-url 等敏感项。
 */
public record AiStatusResponse(
        boolean mockEnabled,
        String model,
        double minSimilarity,
        int topK
) {
}
