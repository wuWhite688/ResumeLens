package com.arthur.jdragresume.controller;

import com.arthur.jdragresume.common.ApiResponse;
import com.arthur.jdragresume.dto.browser.BrowserExtensionAnalyzeRequest;
import com.arthur.jdragresume.dto.browser.BrowserExtensionAnalyzeResponse;
import com.arthur.jdragresume.service.BrowserExtensionService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/browser-extension")
public class BrowserExtensionController {
    private final BrowserExtensionService browserExtensionService;

    public BrowserExtensionController(BrowserExtensionService browserExtensionService) {
        this.browserExtensionService = browserExtensionService;
    }

    @PostMapping("/analyze")
    public ApiResponse<BrowserExtensionAnalyzeResponse> analyze(
            @Valid @RequestBody BrowserExtensionAnalyzeRequest request
    ) {
        return ApiResponse.ok(browserExtensionService.analyze(request));
    }
}
