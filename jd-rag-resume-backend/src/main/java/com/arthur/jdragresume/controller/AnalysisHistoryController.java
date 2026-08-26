package com.arthur.jdragresume.controller;

import com.arthur.jdragresume.common.ApiResponse;
import com.arthur.jdragresume.common.PageResponse;
import com.arthur.jdragresume.dto.analysis.AiAnalysisRequest;
import com.arthur.jdragresume.dto.analysis.AnalysisHistoryRequest;
import com.arthur.jdragresume.dto.analysis.AnalysisHistoryResponse;
import com.arthur.jdragresume.service.AiAnalysisService;
import com.arthur.jdragresume.service.AnalysisHistoryService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/analysis-histories")
public class AnalysisHistoryController {
    private final AnalysisHistoryService analysisHistoryService;
    private final AiAnalysisService aiAnalysisService;

    public AnalysisHistoryController(
            AnalysisHistoryService analysisHistoryService,
            AiAnalysisService aiAnalysisService
    ) {
        this.analysisHistoryService = analysisHistoryService;
        this.aiAnalysisService = aiAnalysisService;
    }

    @GetMapping
    public ApiResponse<PageResponse<AnalysisHistoryResponse>> findAll(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(defaultValue = "") String keyword
    ) {
        return ApiResponse.ok(analysisHistoryService.findAll(page, size, keyword));
    }

    @GetMapping("/{id}")
    public ApiResponse<AnalysisHistoryResponse> findById(@PathVariable Long id) {
        return ApiResponse.ok(analysisHistoryService.findById(id));
    }

    @GetMapping("/latest")
    public ApiResponse<AnalysisHistoryResponse> findLatest(
            @RequestParam Long resumeId,
            @RequestParam Long jobDescriptionId
    ) {
        return ApiResponse.ok(
                analysisHistoryService.findLatest(resumeId, jobDescriptionId).orElse(null)
        );
    }

    @GetMapping("/latest-by-resume")
    public ApiResponse<List<AnalysisHistoryResponse>> findLatestForEachJob(
            @RequestParam Long resumeId
    ) {
        return ApiResponse.ok(analysisHistoryService.findLatestForEachJob(resumeId));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<AnalysisHistoryResponse> create(@Valid @RequestBody AnalysisHistoryRequest request) {
        return ApiResponse.ok(analysisHistoryService.create(request));
    }

    @PostMapping("/ai")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<AnalysisHistoryResponse> analyzeWithAi(@Valid @RequestBody AiAnalysisRequest request) {
        return ApiResponse.ok(aiAnalysisService.analyze(request));
    }

    @PutMapping("/{id}")
    public ApiResponse<AnalysisHistoryResponse> update(@PathVariable Long id, @Valid @RequestBody AnalysisHistoryRequest request) {
        return ApiResponse.ok(analysisHistoryService.update(id, request));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        analysisHistoryService.delete(id);
        return ApiResponse.ok();
    }
}
