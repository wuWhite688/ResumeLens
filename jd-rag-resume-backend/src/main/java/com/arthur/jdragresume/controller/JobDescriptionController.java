package com.arthur.jdragresume.controller;

import com.arthur.jdragresume.common.ApiResponse;
import com.arthur.jdragresume.common.PageResponse;
import com.arthur.jdragresume.dto.job.JobDescriptionBulkImportRequest;
import com.arthur.jdragresume.dto.job.JobDescriptionRequest;
import com.arthur.jdragresume.dto.job.JobDescriptionResponse;
import com.arthur.jdragresume.dto.job.JobSemanticMatchResponse;
import com.arthur.jdragresume.dto.job.JobSourceLookupResponse;
import com.arthur.jdragresume.service.JobDescriptionService;
import com.arthur.jdragresume.service.JobSemanticMatchService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
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
import org.springframework.validation.annotation.Validated;

import java.util.List;

@RestController
@RequestMapping("/api/job-descriptions")
@Validated
public class JobDescriptionController {
    private final JobDescriptionService jobDescriptionService;
    private final JobSemanticMatchService jobSemanticMatchService;

    public JobDescriptionController(
            JobDescriptionService jobDescriptionService,
            JobSemanticMatchService jobSemanticMatchService
    ) {
        this.jobDescriptionService = jobDescriptionService;
        this.jobSemanticMatchService = jobSemanticMatchService;
    }

    @GetMapping
    public ApiResponse<PageResponse<JobDescriptionResponse>> findAll(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(defaultValue = "") String keyword
    ) {
        return ApiResponse.ok(jobDescriptionService.findAll(page, size, keyword));
    }

    @GetMapping("/{id}")
    public ApiResponse<JobDescriptionResponse> findById(@PathVariable Long id) {
        return ApiResponse.ok(jobDescriptionService.findById(id));
    }

    @GetMapping("/matches")
    public ApiResponse<List<JobSemanticMatchResponse>> findSemanticMatches(
            @RequestParam Long resumeId,
            @RequestParam(defaultValue = "200") @Min(1) @Max(200) int limit
    ) {
        return ApiResponse.ok(jobSemanticMatchService.rank(resumeId, limit));
    }

    @PostMapping("/matches/refresh")
    public ApiResponse<Void> refreshSemanticMatchEmbeddings(@RequestParam Long resumeId) {
        jobSemanticMatchService.refreshStaleEmbeddings(resumeId);
        return ApiResponse.ok();
    }

    @GetMapping("/source")
    public ApiResponse<JobSourceLookupResponse> findBySource(
            @RequestParam @NotBlank @Size(max = 32) String sourcePlatform,
            @RequestParam @NotBlank @Size(max = 160) String sourceJobId
    ) {
        return ApiResponse.ok(jobDescriptionService.findBySource(sourcePlatform, sourceJobId));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<JobDescriptionResponse> create(@Valid @RequestBody JobDescriptionRequest request) {
        return ApiResponse.ok(jobDescriptionService.create(request));
    }

    @PostMapping("/import")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<List<JobDescriptionResponse>> bulkImport(@Valid @RequestBody JobDescriptionBulkImportRequest request) {
        return ApiResponse.ok(jobDescriptionService.bulkImport(request));
    }

    @PutMapping("/{id}")
    public ApiResponse<JobDescriptionResponse> update(@PathVariable Long id, @Valid @RequestBody JobDescriptionRequest request) {
        return ApiResponse.ok(jobDescriptionService.update(id, request));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id) {
        jobDescriptionService.delete(id);
    }
}
