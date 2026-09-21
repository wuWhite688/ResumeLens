package com.arthur.jdragresume.mapper;

import com.arthur.jdragresume.dto.JobListItem;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface JobDescriptionMapper {
    List<JobListItem> searchJobs(@Param("userId") Long userId,
                                 @Param("keyword") String keyword,
                                 @Param("location") String location,
                                 @Param("offset") long offset,
                                 @Param("size") int size);
}
