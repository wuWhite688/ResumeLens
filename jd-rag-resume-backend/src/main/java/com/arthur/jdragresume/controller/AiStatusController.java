package com.arthur.jdragresume.controller;

import com.arthur.jdragresume.ai.AiProperties;
import com.arthur.jdragresume.common.ApiResponse;
import com.arthur.jdragresume.dto.ai.AiStatusResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/ai")
public class AiStatusController {
    private final AiProperties aiProperties;

    public AiStatusController(AiProperties aiProperties) {
        this.aiProperties = aiProperties;
    }

    @GetMapping("/status")
    public ApiResponse<AiStatusResponse> status() {
        String model = aiProperties.getModel();
        return ApiResponse.ok(new AiStatusResponse(
                aiProperties.isMockEnabled(),
                model == null ? "" : model.trim()
        ));
    }
}
