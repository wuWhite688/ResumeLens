package com.arthur.jdragresume.controller;

import com.arthur.jdragresume.ai.AiProperties;
import com.arthur.jdragresume.common.ApiResponse;
import com.arthur.jdragresume.dto.ai.AiStatusResponse;
import com.arthur.jdragresume.rag.RagProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/ai")
public class AiStatusController {
    private final AiProperties aiProperties;
    private final RagProperties ragProperties;
    private final long pendingTimeoutMinutes;

    public AiStatusController(
            AiProperties aiProperties,
            RagProperties ragProperties,
            @Value("${app.analysis.pending-timeout-minutes:10}") long pendingTimeoutMinutes
    ) {
        this.aiProperties = aiProperties;
        this.ragProperties = ragProperties;
        this.pendingTimeoutMinutes = Math.max(1, pendingTimeoutMinutes);
    }

    /**
     * 检索参数一并返回，供前端展示当前生效的阈值与 Top-K。
     * 界面若把这些值写死，配置一改就会与实际行为脱节。
     */
    @GetMapping("/status")
    public ApiResponse<AiStatusResponse> status() {
        String model = aiProperties.getModel();
        return ApiResponse.ok(new AiStatusResponse(
                aiProperties.isMockEnabled(),
                model == null ? "" : model.trim(),
                ragProperties.getMinSimilarity(),
                ragProperties.getTopK(),
                pendingTimeoutMinutes
        ));
    }
}
