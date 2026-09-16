package com.arthur.jdragresume.controller;

import com.arthur.jdragresume.common.ApiResponse;
import com.arthur.jdragresume.dto.JobListItem;
import com.arthur.jdragresume.service.JobQueryService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/job-query")
@Validated
public class JobQueryController {

    private final JobQueryService jobQueryService;

    public JobQueryController(JobQueryService jobQueryService) {
        this.jobQueryService = jobQueryService;
    }

    @GetMapping
    public ApiResponse<List<JobListItem>> search(
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "10") @Min(1) @Max(50) int size,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String location
    ) {
        return ApiResponse.ok(jobQueryService.search(keyword, location, page, size));
    }
}