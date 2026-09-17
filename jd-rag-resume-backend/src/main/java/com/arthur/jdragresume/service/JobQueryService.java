package com.arthur.jdragresume.service;

import com.arthur.jdragresume.dto.JobListItem;
import com.arthur.jdragresume.entity.AppUser;
import com.arthur.jdragresume.mapper.JobDescriptionMapper;
import com.arthur.jdragresume.security.CurrentUserService;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class JobQueryService {

    private final JobDescriptionMapper jobDescriptionMapper;
    private final CurrentUserService currentUserService;

    public JobQueryService(JobDescriptionMapper jobDescriptionMapper,
                           CurrentUserService currentUserService) {
        this.jobDescriptionMapper = jobDescriptionMapper;
        this.currentUserService = currentUserService;
    }

    public List<JobListItem> search(String keyword, String location, int page, int size) {
        AppUser user = currentUserService.getCurrentUser();
        int offset = page * size;
        return jobDescriptionMapper.searchJobs(user.getId(), keyword, location, offset, size);
    }
}