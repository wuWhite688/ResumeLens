package com.arthur.jdragresume.controller;

import com.arthur.jdragresume.common.ApiResponse;
import com.arthur.jdragresume.common.PageResponse;
import com.arthur.jdragresume.dto.job.JobDescriptionBulkImportRequest;
import com.arthur.jdragresume.dto.job.JobDescriptionRequest;
import com.arthur.jdragresume.dto.job.JobDescriptionResponse;
import com.arthur.jdragresume.service.JobDescriptionService;
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
@RequestMapping("/api/job-descriptions")
public class JobDescriptionController {
    private final JobDescriptionService jobDescriptionService;

    public JobDescriptionController(JobDescriptionService jobDescriptionService) {
        this.jobDescriptionService = jobDescriptionService;
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
